import {parseAdvancement, type AdvancementFile} from "./advancement.ts";
import {boundedJson, mib} from "../memoryBudget.ts";

export interface AdvancementSelection {
    roots: Array<{id: string; path: string}>;
    root: string | null;
    files: AdvancementFile[];
    count: number;
    unavailable: number;
    nextCursor: number | null;
}

export class AdvancementTreeError extends Error {
    public roots: AdvancementSelection["roots"];
    public constructor(message: string, roots: AdvancementSelection["roots"]) {super(message); this.roots = roots;}
}

export function advancementStructure(files: AdvancementFile[]): Array<{path: string; parent?: string; display: boolean}> {
    if (files.length > 256) throw new Error(`Preview supports at most 256 workspace/open advancement overrides`);
    return files.flatMap(file => {
        const node = parseAdvancement(file);
        return node ? [{path: file.path, parent: node.parent, display: Boolean(node.display)}] : [];
    });
}

export async function loadAdvancementTree(signal: AbortSignal, path: string | undefined, root: string | undefined,
                                          overrides: ReturnType<typeof advancementStructure>): Promise<AdvancementSelection> {
    const files: AdvancementFile[] = [];
    const body = JSON.stringify({path, root, overrides});
    if (body.length > 128 * 1024) throw new Error("Advancement overrides exceed the preview metadata budget");
    let cursor = 0;
    let characters = 0;
    let selectedRoot = root;
    while (!signal.aborted) {
        const response = await fetch(`/api/advancement-tree?cursor=${cursor}&limit=32`, {
            signal, method: "POST", headers: {"Content-Type": "application/json"},
            body: selectedRoot === root ? body : JSON.stringify({path, root: selectedRoot, overrides})
        });
        if (!response.ok) {
            const detail = await boundedJson<{error?: string; roots?: AdvancementSelection["roots"]}>(response, 512 * 1024).catch(() => ({} as {error?: string; roots?: AdvancementSelection["roots"]}));
            throw new AdvancementTreeError(detail.error ?? `Advancements unavailable (${response.status})`, (detail.roots ?? []).slice(0, 2048));
        }
        const page = await boundedJson<AdvancementSelection>(response, 2 * mib);
        if (page.count > 4096 || page.roots.length > 2048) throw new Error("Advancement tree exceeds the preview limit");
        selectedRoot = page.root ?? undefined;
        for (const file of page.files) {
            characters += file.content.length + file.path.length;
            if (files.length >= 4096 || characters > 4 * mib) throw new Error("Advancement tree exceeds the preview memory budget");
            files.push(file);
        }
        if (page.nextCursor == null) return {...page, files};
        if (!Number.isSafeInteger(page.nextCursor) || page.nextCursor <= cursor || page.nextCursor > 4096) {
            throw new Error("Invalid advancement page cursor");
        }
        cursor = page.nextCursor;
    }
    signal.throwIfAborted();
    throw new Error("Advancement loading aborted");
}
