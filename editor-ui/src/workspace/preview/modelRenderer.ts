import * as THREE from "three";
import type {ChestStack} from "./loot/lootTable.ts";
import type {FeatureBlock} from "./features/configuredFeature.ts";
import {boundedBytes, boundedJson, BudgetCache, mib} from "./memoryBudget";
import {indexedAssetReads} from "./assetLookup";
import type {VirtualAssets} from "./assetLookup";

export interface BlockScene {
    group: THREE.Group;
    bounds: THREE.Box3;
    sortTranslucent(camera: THREE.Camera): void;
    dispose(): void;
}

interface AssetManifest {
    version: string;
    sources: Array<{id: number; name: string}>;
}

interface RendererModule {
    configure(options: {THREE: typeof THREE}): void;
    prepareAssets(sources: Array<Blob | VirtualAssets>, options: {cache: boolean; version: string}): Promise<unknown>;
    renderItem(options: {id: string; components?: Record<string, unknown>; assets: unknown;
        width: number; height: number; version: string}): Promise<HTMLCanvasElement>;
    createScene(assets: unknown, blocks: FeatureBlock[], options: {version: string; defaults: "game";
        randomOffset: boolean; lighting: "world"; origin: [number, number, number];
        shouldCancel: () => boolean}): Promise<BlockScene | null>;
    disposeCache(assets: unknown): void;
}

interface Session {
    renderer: RendererModule;
    assets: unknown;
    version: string;
    icons: BudgetCache<string, string | null>;
    pendingIcons: Map<string, Promise<string | undefined>>;
}

let rendererPromise: Promise<RendererModule> | undefined;
let sessionPromise: Promise<Session> | undefined;
let stopped = false;
const controller = new AbortController();

class WorkQueue {
    private active = 0;
    private waiting: Array<() => void> = [];
    public constructor(private concurrency: number, private limit: number) {}
    public async run<T>(work: () => Promise<T>): Promise<T> {
        if (this.active >= this.concurrency) {
            if (this.waiting.length >= this.limit) throw new Error("Preview work queue is full");
            await new Promise<void>(resolve => this.waiting.push(resolve));
        } else this.active++;
        try {return await work();}
        finally {
            const next = this.waiting.shift();
            if (next) next(); else this.active--;
        }
    }
}
const iconQueue = new WorkQueue(1, 32);
const assetQueue = new WorkQueue(2, 128);
let renderBudget: {paths: Set<string>; bytes: number; limit: number; exceeded: boolean} | undefined;

function chargeAsset(path: string, bytes: Uint8Array): void {
    const budget = renderBudget;
    if (!budget || budget.paths.has(path)) return;
    let size = bytes.length;
    if (path.endsWith(".png") && bytes.length >= 24) {
        const header = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
        size += header.getUint32(16) * header.getUint32(20) * 4;
    }
    if (budget.exceeded || budget.bytes + size > budget.limit || budget.paths.size >= 256) {
        budget.exceeded = true;
        throw new Error("Preview textures/models exceed the render memory budget");
    }
    budget.paths.add(path);
    budget.bytes += size;
}

export function stopRenderingAssets(): void {
    stopped = true;
    controller.abort();
    void sessionPromise?.then(session => {session.icons.clear(); session.renderer.disposeCache(session.assets);}).catch(() => undefined);
    sessionPromise = undefined;
}

async function loadRenderer(): Promise<RendererModule> {
    if (!rendererPromise) {
        rendererPromise = import("block-model-renderer").then(module => {
            const renderer = module as RendererModule;
            renderer.configure({THREE});
            return renderer;
        }).catch(reason => {
            rendererPromise = undefined;
            throw reason;
        });
    }
    return rendererPromise;
}

async function loadSession(): Promise<Session> {
    if (stopped) throw new Error("Editor memory safeguard stopped asset rendering");
    if (!sessionPromise) {
        sessionPromise = (async () => {
            const [renderer, manifestResponse] = await Promise.all([
                loadRenderer(), fetch("/api/assets", {signal: controller.signal})
            ]);
            if (!manifestResponse.ok) throw new Error(`Asset list unavailable (${manifestResponse.status})`);
            const manifest = await boundedJson<AssetManifest>(manifestResponse);
            if (!manifest.sources.length) throw new Error("No client assets were found in loaded packs or mod jars.");
            const assets = await renderer.prepareAssets([assetHandler()], {cache: false, version: manifest.version});
            return {renderer, assets, version: manifest.version, icons: new BudgetCache<string, string | null>(2 * mib, 128), pendingIcons: new Map()};
        })().catch(reason => {
            sessionPromise = undefined;
            throw reason;
        });
    }
    return sessionPromise;
}

function assetHandler(): VirtualAssets {
    const files = new BudgetCache<string, Uint8Array | null>(8 * mib, 128);
    const directories = new BudgetCache<string, string[]>(512 * 1024, 128);
    const atlasPaths = new Set<string>();
    let atlasBytes = 0;
    return indexedAssetReads({
        async read(path) {
            if (stopped) throw new Error("Editor stopped");
            if (renderBudget?.exceeded) throw new Error("Render budget exceeded");
            const cached = files.get(path);
            if (cached !== undefined) {
                if (cached) chargeAsset(path, cached);
                return cached;
            }
            return assetQueue.run(async () => {
                const response = await fetch(`/api/asset-resolved?path=${encodeURIComponent(path)}`, {signal: controller.signal});
                if (response.status === 404) {
                    files.set(path, null, path.length * 2 + 32);
                    return null;
                }
                if (!response.ok) throw new Error(`Asset read failed (${response.status}): ${path}`);
                const bytes = await boundedBytes(response, path.endsWith(".json") ? 2 * mib : 8 * mib);
                chargeAsset(path, bytes);
                if (/\/atlases\/.*\.json$/.test(path) && !atlasPaths.has(path)) {
                    atlasBytes += bytes.length;
                    if (atlasBytes > 2 * mib || atlasPaths.size >= 512) throw new Error("Atlas metadata exceeds the renderer memory budget");
                    atlasPaths.add(path);
                }
                files.set(path, bytes, bytes.length + path.length * 2);
                return bytes;
            });
        },
        async list(directory) {
            if (stopped) throw new Error("Editor stopped");
            const cached = directories.get(directory);
            if (cached) return cached;
            return assetQueue.run(async () => {
                const response = await fetch(`/api/asset-list-resolved?dir=${encodeURIComponent(directory)}`, {signal: controller.signal});
                if (!response.ok) throw new Error(`Asset list failed (${response.status}): ${directory}`);
                const names = await boundedJson<string[]>(response, 512 * 1024);
                if (names.length > 32768 || (directory === "assets" && names.length > 512)) throw new Error("Asset directory exceeds the renderer lookup budget");
                directories.set(directory, names, directory.length * 2 + names.reduce((bytes, name) => bytes + name.length * 2 + 32, 0));
                return names;
            });
        }
    });
}

export async function createBlockScene(blocks: FeatureBlock[], shouldCancel: () => boolean): Promise<BlockScene | null> {
    const session = await loadSession();
    return iconQueue.run(async () => {
        if (stopped || shouldCancel()) return null;
        renderBudget = {paths: new Set(), bytes: 0, limit: 64 * mib, exceeded: false};
        let assets: unknown;
        try {
            assets = await session.renderer.prepareAssets(Object.assign([], session.assets), {cache: true, version: session.version});
            const scene = await session.renderer.createScene(assets, blocks, {version: session.version, defaults: "game",
                randomOffset: true, lighting: "world", origin: [0, 64, 0], shouldCancel: () => stopped || shouldCancel()});
            if (renderBudget?.exceeded) {scene?.dispose(); throw new Error("Scene assets exceed the preview memory budget");}
            if (!scene) {session.renderer.disposeCache(assets); return null;}
            return {...scene, dispose() {
                scene.dispose();
                scene.group.traverse(object => {
                    if (object instanceof THREE.Mesh || object instanceof THREE.Line || object instanceof THREE.Points) object.geometry.dispose();
                });
                session.renderer.disposeCache(assets);
            }};
        } catch (reason) {if (assets) session.renderer.disposeCache(assets); throw reason;}
        finally {renderBudget = undefined;}
    });
}

export async function renderItemIcon(stack: ChestStack, shouldCancel: () => boolean = () => false): Promise<string | undefined> {
    let session: Session;
    try {session = await loadSession();} catch {return undefined;}
    const key = JSON.stringify([stack.id, stack.components ?? null]);
    const cached = session.icons.get(key);
    if (cached !== undefined) return cached ?? undefined;
    const pending = session.pendingIcons.get(key);
    if (pending) return pending;
    const rendering = iconQueue.run(async () => {
        if (stopped || shouldCancel()) return undefined;
        renderBudget = {paths: new Set(), bytes: 0, limit: 16 * mib, exceeded: false};
        let assets: unknown;
        try {
            assets = await session.renderer.prepareAssets(Object.assign([], session.assets), {cache: true, version: session.version});
            const itemModel = stack.components?.["minecraft:item_model"];
            const modelId = typeof itemModel === "string" ? itemModel : stack.id;
            const canvas = await session.renderer.renderItem({id: modelId, components: stack.components,
                assets, width: 48, height: 48, version: session.version});
            if (renderBudget?.exceeded) throw new Error("Item assets exceed the preview memory budget");
            const pixels = canvas.getContext("2d")?.getImageData(0, 0, canvas.width, canvas.height).data;
            if (!pixels?.some((value, index) => index % 4 === 3 && value > 0)) {
                session.icons.set(key, null, key.length * 2 + 32);
                return undefined;
            }
            const icon = canvas.toDataURL("image/png");
            session.icons.set(key, icon, (key.length + icon.length) * 2 + 32);
            return icon;
        } catch {
            session.icons.set(key, null, key.length * 2 + 32);
            return undefined;
        } finally {
            if (assets) session.renderer.disposeCache(assets);
            renderBudget = undefined;
        }
    }).catch(() => undefined).finally(() => session.pendingIcons.delete(key));
    session.pendingIcons.set(key, rendering);
    return rendering;
}

export async function renderItemIcons(slots: Array<ChestStack | null>,
                                      onIcon?: (index: number, icon: string) => void,
                                      shouldCancel: () => boolean = () => false): Promise<{icons: Record<number, string>; failed: string[]}> {
    const icons: Record<number, string> = {};
    const failed: string[] = [];
    let next = 0;
    const workers = Array.from({length: Math.min(2, slots.length)}, async () => {
        while (!shouldCancel() && next < slots.length) {
            const index = next++;
            const stack = slots[index];
            if (!stack) continue;
            const icon = await renderItemIcon(stack, shouldCancel);
            if (icon) {
                icons[index] = icon;
                if (!shouldCancel()) onIcon?.(index, icon);
            } else failed.push(stack.id);
        }
    });
    await Promise.all(workers);
    return {icons, failed};
}
