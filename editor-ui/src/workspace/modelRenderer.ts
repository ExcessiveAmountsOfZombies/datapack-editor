import * as THREE from "three";
import type {ChestStack} from "./lootTable";

const rendererUrl = "https://cdn.jsdelivr.net/npm/block-model-renderer@2/dist/block-model-renderer.min.js";

interface AssetManifest {
    version: string;
    sources: Array<{id: number; name: string}>;
}

interface VirtualAssets {
    read(path: string): Promise<Uint8Array | null>;
    list(directory: string): Promise<string[]>;
}

interface ItemDefinition {
    model?: {type?: string; base_model?: unknown};
}

interface RendererModule {
    configure(options: {THREE: typeof THREE}): void;
    prepareAssets(sources: VirtualAssets[], options: {cache: boolean; version: string}): Promise<unknown>;
    renderItem(options: {id: string; components?: Record<string, unknown>; assets: unknown;
        width: number; height: number; version: string}): Promise<HTMLCanvasElement>;
}

interface Session {
    renderer: RendererModule;
    assets: unknown;
    version: string;
    icons: Map<string, string>;
}

let rendererPromise: Promise<RendererModule> | undefined;
let sessionPromise: Promise<Session> | undefined;
let sessionToken: string | undefined;

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

async function loadSession(token: string): Promise<Session> {
    if (sessionToken !== token) {
        sessionToken = token;
        sessionPromise = undefined;
    }
    if (!sessionPromise) {
        sessionPromise = (async () => {
            const renderer = await loadRenderer();
            const manifestResponse = await fetch("/api/assets", {headers: {Authorization: `Bearer ${token}`}});
            if (!manifestResponse.ok) throw new Error(`Asset list unavailable (${manifestResponse.status})`);
            const manifest = await manifestResponse.json() as AssetManifest;
            if (!manifest.sources.length) throw new Error("No client assets were found in loaded packs or mod jars.");
            const sources = manifest.sources.map(source => assetHandler(source.id, token));
            const assets = await renderer.prepareAssets(sources, {cache: true, version: manifest.version});
            return {renderer, assets, version: manifest.version, icons: new Map()};
        })().catch(reason => {
            sessionPromise = undefined;
            throw reason;
        });
    }
    return sessionPromise;
}

function assetHandler(source: number, token: string): VirtualAssets {
    const files = new Map<string, Promise<Uint8Array | null>>();
    const directories = new Map<string, Promise<string[]>>();
    return {
        read(path) {
            let pending = files.get(path);
            if (!pending) {
                pending = fetch(`/api/asset-file?source=${source}&path=${encodeURIComponent(path)}`,
                    {headers: {Authorization: `Bearer ${token}`}}).then(async response => {
                    if (response.status === 404) return null;
                    if (!response.ok) throw new Error(`Asset read failed (${response.status}): ${path}`);
                    return adaptItemDefinition(path, new Uint8Array(await response.arrayBuffer()));
                });
                files.set(path, pending);
            }
            return pending;
        },
        list(directory) {
            let pending = directories.get(directory);
            if (!pending) {
                pending = fetch(`/api/asset-list?source=${source}&dir=${encodeURIComponent(directory)}`,
                    {headers: {Authorization: `Bearer ${token}`}}).then(async response => {
                    if (!response.ok) throw new Error(`Asset list failed (${response.status}): ${directory}`);
                    return response.json() as Promise<string[]>;
                });
                directories.set(directory, pending);
            }
            return pending;
        }
    };
}

function adaptItemDefinition(path: string, bytes: Uint8Array): Uint8Array {
    // had to do this because neoforge overrides the models for vanilla armors and i couldn't see them
    //  there might be more that needs to be done here, but i think it's ok for now
    if (!/^assets\/[^/]+\/items\/.+\.json$/.test(path)) return bytes;
    const definition = JSON.parse(new TextDecoder().decode(bytes)) as ItemDefinition;
    if (definition.model?.type !== "neoforge:trimmed_armor" || !definition.model.base_model) return bytes;
    definition.model = definition.model.base_model as ItemDefinition["model"];
    return new TextEncoder().encode(JSON.stringify(definition));
}

export async function renderItemIcons(slots: Array<ChestStack | null>, token: string): Promise<{icons: Record<number, string>; failed: string[]}> {
    const {renderer, assets, version, icons: cache} = await loadSession(token);
    const icons: Record<number, string> = {};
    const failed: string[] = [];
    for (let index = 0; index < slots.length; index++) {
        const stack = slots[index];
        if (!stack) continue;
        const key = JSON.stringify([stack.id, stack.components ?? null]);
        const cached = cache.get(key);
        if (cached) {
            icons[index] = cached;
            continue;
        }
        try {
            const itemModel = stack.components?.["minecraft:item_model"];
            const modelId = typeof itemModel === "string" ? itemModel : stack.id;
            const canvas = await renderer.renderItem({id: modelId, components: stack.components, assets,
                width: 64, height: 64, version});
            const pixels = canvas.getContext("2d")?.getImageData(0, 0, canvas.width, canvas.height).data;
            if (!pixels?.some((value, index) => index % 4 === 3 && value > 0)) {
                failed.push(stack.id);
                continue;
            }
            icons[index] = canvas.toDataURL("image/png");
            cache.set(key, icons[index]);
        } catch {
            failed.push(stack.id);
        }
    }
    return {icons, failed};
}
