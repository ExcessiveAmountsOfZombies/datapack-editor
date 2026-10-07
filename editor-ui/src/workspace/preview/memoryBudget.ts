export const mib = 1024 * 1024;

export async function boundedBytes(response: Response, limit: number = 2 * mib): Promise<Uint8Array> {
    if (Number(response.headers.get("Content-Length")) > limit) {
        await response.body?.cancel();
        throw new Error("Resource exceeds the editor's memory budget");
    }
    const reader = response.body?.getReader();
    if (!reader) return new Uint8Array();
    const chunks: Uint8Array[] = [];
    let length = 0;
    try {
        while (true) {
            const part = await reader.read();
            if (part.done) break;
            length += part.value.byteLength;
            if (length > limit) throw new Error("Resource exceeds the editor's memory budget");
            chunks.push(part.value);
        }
    } catch (reason) {
        await reader.cancel().catch(() => undefined);
        throw reason;
    } finally {
        reader.releaseLock();
    }
    const result = new Uint8Array(length);
    let offset = 0;
    for (const chunk of chunks) {result.set(chunk, offset); offset += chunk.length;}
    return result;
}

export async function boundedJson<T>(response: Response, limit: number = 2 * mib): Promise<T> {
    return JSON.parse(new TextDecoder().decode(await boundedBytes(response, limit))) as T;
}

export class BudgetCache<K, V> {
    private entries = new Map<K, {value: V; bytes: number}>();
    private bytes = 0;
    private maxBytes: number;
    private maxEntries: number;
    public constructor(maxBytes: number, maxEntries: number) {this.maxBytes = maxBytes; this.maxEntries = maxEntries;}
    public get(key: K): V | undefined {
        const entry = this.entries.get(key);
        if (!entry) return undefined;
        this.entries.delete(key);
        this.entries.set(key, entry);
        return entry.value;
    }
    public set(key: K, value: V, bytes: number): void {
        const previous = this.entries.get(key);
        if (previous) {this.bytes -= previous.bytes; this.entries.delete(key);}
        if (bytes > this.maxBytes) return;
        while (this.entries.size && (this.entries.size >= this.maxEntries || this.bytes + bytes > this.maxBytes)) {
            const first = this.entries.keys().next().value!;
            this.bytes -= this.entries.get(first)!.bytes;
            this.entries.delete(first);
        }
        this.entries.set(key, {value, bytes});
        this.bytes += bytes;
    }
    public clear(): void {this.entries.clear(); this.bytes = 0;}
}
