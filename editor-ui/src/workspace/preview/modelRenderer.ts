import * as THREE from "three";
import type {ChestStack} from "./lootTable";
import type {FeatureBlock} from "./configuredFeature";

export interface BlockScene {
    group: THREE.Group;
    bounds: THREE.Box3;
    sortTranslucent(camera: THREE.Camera): void;
    dispose(): void;
}

const rendererUrl = "https://cdn.jsdelivr.net/npm/block-model-renderer@2/dist/block-model-renderer.min.js";

interface AssetManifest {
    version: string;
    sources: Array<{id: number; name: string}>;
}

interface VirtualAssets {
    read(path: string): Promise<Uint8Array | null>;
    list(directory: string): Promise<string[]>;
}

interface RendererModule {
    configure(options: {THREE: typeof THREE}): void;
    prepareAssets(sources: Array<Blob | VirtualAssets>, options: {cache: boolean; version: string}): Promise<unknown>;
    renderItem(options: {id: string; components?: Record<string, unknown>; assets: unknown;
        width: number; height: number; version: string}): Promise<HTMLCanvasElement>;
    createScene(assets: unknown, blocks: FeatureBlock[], options: {version: string; defaults: "game";
        randomOffset: boolean; lighting: "world"; origin: [number, number, number];
        shouldCancel: () => boolean}): Promise<BlockScene | null>;
}

interface Session {
    renderer: RendererModule;
    assets: unknown;
    version: string;
    icons: Map<string, string | null>;
    pendingIcons: Map<string, Promise<string | undefined>>;
}

let rendererPromise: Promise<RendererModule> | undefined;
let sessionPromise: Promise<Session> | undefined;

async function loadRenderer(): Promise<RendererModule> {
    if (!rendererPromise) {
        rendererPromise = import(/* @vite-ignore */ rendererUrl).then(module => {
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
    if (!sessionPromise) {
        sessionPromise = (async () => {
            const [renderer, manifestResponse, bundleResponse] = await Promise.all([
                loadRenderer(), fetch("/api/assets"), fetch("/api/asset-bundle")
            ]);
            if (!manifestResponse.ok) throw new Error(`Asset list unavailable (${manifestResponse.status})`);
            const manifest = await manifestResponse.json() as AssetManifest;
            if (!manifest.sources.length) throw new Error("No client assets were found in loaded packs or mod jars.");
            const bundled = bundleResponse.ok;
            const sources: Array<Blob | VirtualAssets> = bundled
                ? [await bundleResponse.blob(), assetHandler()] : [assetHandler()];
            let assets: unknown;
            try {
                assets = await renderer.prepareAssets(sources, {cache: true, version: manifest.version});
            } catch (reason) {
                if (!bundled) throw reason;
                assets = await renderer.prepareAssets([assetHandler()],
                    {cache: true, version: manifest.version});
            }
            return {renderer, assets, version: manifest.version, icons: new Map(), pendingIcons: new Map()};
        })().catch(reason => {
            sessionPromise = undefined;
            throw reason;
        });
    }
    return sessionPromise;
}

function assetHandler(): VirtualAssets {
    const files = new Map<string, Promise<Uint8Array | null>>();
    const directories = new Map<string, Promise<string[]>>();
    return {
        read(path) {
            let pending = files.get(path);
            if (!pending) {
                pending = fetch(`/api/asset-resolved?path=${encodeURIComponent(path)}`).then(async response => {
                    if (response.status === 404) return null;
                    if (!response.ok) throw new Error(`Asset read failed (${response.status}): ${path}`);
                    return new Uint8Array(await response.arrayBuffer());
                });
                files.set(path, pending);
            }
            return pending;
        },
        list(directory) {
            let pending = directories.get(directory);
            if (!pending) {
                pending = fetch(`/api/asset-list-resolved?dir=${encodeURIComponent(directory)}`).then(async response => {
                    if (!response.ok) throw new Error(`Asset list failed (${response.status}): ${directory}`);
                    return response.json() as Promise<string[]>;
                });
                directories.set(directory, pending);
            }
            return pending;
        }
    };
}

export function preloadItemAssets(): void {
    void loadSession().catch(() => undefined);
}

export async function createBlockScene(blocks: FeatureBlock[], shouldCancel: () => boolean): Promise<BlockScene | null> {
    const session = await loadSession();
    if (shouldCancel()) return null;
    return session.renderer.createScene(session.assets, blocks, {version: session.version, defaults: "game",
        randomOffset: true, lighting: "world", origin: [0, 64, 0], shouldCancel});
}

export async function renderItemIcon(stack: ChestStack): Promise<string | undefined> {
    const session = await loadSession();
    const key = JSON.stringify([stack.id, stack.components ?? null]);
    if (session.icons.has(key)) return session.icons.get(key) ?? undefined;
    const pending = session.pendingIcons.get(key);
    if (pending) return pending;
    const rendering = (async () => {
        try {
            const itemModel = stack.components?.["minecraft:item_model"];
            const modelId = typeof itemModel === "string" ? itemModel : stack.id;
            const canvas = await session.renderer.renderItem({id: modelId, components: stack.components,
                assets: session.assets, width: 48, height: 48, version: session.version});
            const pixels = canvas.getContext("2d")?.getImageData(0, 0, canvas.width, canvas.height).data;
            if (!pixels?.some((value, index) => index % 4 === 3 && value > 0)) {
                session.icons.set(key, null);
                return undefined;
            }
            const icon = canvas.toDataURL("image/png");
            session.icons.set(key, icon);
            return icon;
        } catch {
            session.icons.set(key, null);
            return undefined;
        } finally {
            session.pendingIcons.delete(key);
        }
    })();
    session.pendingIcons.set(key, rendering);
    return rendering;
}

export async function renderItemIcons(slots: Array<ChestStack | null>,
                                      onIcon?: (index: number, icon: string) => void): Promise<{icons: Record<number, string>; failed: string[]}> {
    const icons: Record<number, string> = {};
    const failed: string[] = [];
    let next = 0;
    const workers = Array.from({length: Math.min(6, slots.length)}, async () => {
        while (next < slots.length) {
            const index = next++;
            const stack = slots[index];
            if (!stack) continue;
            const icon = await renderItemIcon(stack);
            if (icon) {
                icons[index] = icon;
                onIcon?.(index, icon);
            } else failed.push(stack.id);
        }
    });
    await Promise.all(workers);
    return {icons, failed};
}
