export interface VirtualAssets {
    read(path: string): Promise<Uint8Array | null>;
    list(directory: string): Promise<string[]>;
}

function sharedLookup<T>(pending: Map<string, Promise<T>>, path: string, work: () => Promise<T>): Promise<T> {
    const existing = pending.get(path);
    if (existing) return existing;
    if (pending.size >= 128) return Promise.reject(new Error("Asset lookup queue is full"));
    const lookup = Promise.resolve().then(work).finally(() => pending.delete(path));
    pending.set(path, lookup);
    return lookup;
}

// Renderer atlas probes must consult the resolved directory index before fetching bytes.
// The underlying provider owns the bounded caches and HTTP queues.
export function indexedAssetReads(provider: VirtualAssets): VirtualAssets {
    const reads = new Map<string, Promise<Uint8Array | null>>();
    const lists = new Map<string, Promise<string[]>>();
    const indexed: VirtualAssets = {
        read(path) {
            return sharedLookup(reads, path, async () => {
                if (path === "pack.mcmeta") return provider.read(path);
                const separator = path.lastIndexOf("/");
                if (separator < 0) return null;
                const names = await indexed.list(path.slice(0, separator));
                if (!names.includes(path.slice(separator + 1))) return null;
                return provider.read(path);
            });
        },
        list(directory) {
            return sharedLookup(lists, directory, async () => {
                if (directory !== "assets") {
                    const separator = directory.lastIndexOf("/");
                    if (separator < 0) return [];
                    const parent = directory.slice(0, separator);
                    const namespaceRoot = /^assets\/([^/]+)$/.exec(parent);
                    // Minecraft rejects an empty resource prefix, so namespace-root
                    // listings can omit real top-level directories. Query them directly.
                    const names = await indexed.list(namespaceRoot ? "assets" : parent);
                    if (!names.includes(namespaceRoot ? namespaceRoot[1] : directory.slice(separator + 1))) return [];
                }
                return provider.list(directory);
            });
        }
    };
    return indexed;
}
