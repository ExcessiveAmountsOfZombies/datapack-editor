export interface WorkspaceEntry {
    name: string;
    path: string;
    type: "file" | "directory";
    source?: "workspace" | "datapack";
    readOnly?: boolean;
    editable?: boolean;
    children?: WorkspaceEntry[];
}

export interface WorkspaceTree {
    root: string;
    readOnly?: boolean;
    fileCount?: number;
    entries: WorkspaceEntry[];
}

export const datapackPrefix = "datapack://";

export function entrySource(entry: WorkspaceEntry): "workspace" | "datapack" {
    return entry.source ?? "workspace";
}

export function entryKey(entry: WorkspaceEntry) {
    return entrySource(entry) === "datapack" ? `${datapackPrefix}${entry.path}` : entry.path;
}

export function isDatapackKey(key: string) {
    return key.startsWith(datapackPrefix);
}

export function displayPath(key: string) {
    return isDatapackKey(key) ? key.slice(datapackPrefix.length) : key;
}

export function ancestorKeys(entry: WorkspaceEntry) {
    const segments = entry.path.split("/");
    const prefix = entrySource(entry) === "datapack" ? datapackPrefix : "";
    return segments.slice(0, -1).map((_, index) => prefix + segments.slice(0, index + 1).join("/"));
}

export function datapackOverridePath(path: string) {
    const dataPath = path.indexOf("/data/");
    return dataPath < 0 ? undefined : path.slice(dataPath + 1);
}

export function fileName(path: string) {
    return path.split("/").at(-1) ?? path;
}

export function findFirstEditable(entries: WorkspaceEntry[]): WorkspaceEntry | undefined {
    for (const entry of entries) {
        if (entry.type === "file" && entry.editable) return entry;
        const nested = findFirstEditable(entry.children ?? []);
        if (nested) return nested;
    }
    return undefined;
}

export function findEntry(entries: WorkspaceEntry[] | undefined, path: string | undefined): WorkspaceEntry | undefined {
    if (!path) return undefined;
    for (const entry of entries ?? []) {
        if (entry.path === path) return entry;
        const nested = findEntry(entry.children, path);
        if (nested) return nested;
    }
    return undefined;
}

export function selectedDirectory(entries: WorkspaceEntry[] | undefined, selectedPath: string | undefined) {
    const selected = findEntry(entries, selectedPath);
    if (selected?.type === "directory") return selected.path;
    if (!selectedPath?.includes("/")) return "";
    return selectedPath.slice(0, selectedPath.lastIndexOf("/"));
}

export function workspaceMoveTarget(entries: WorkspaceEntry[] | undefined, entry: WorkspaceEntry,
                                    destination: string): string | undefined {
    if (entrySource(entry) !== "workspace" || entry.readOnly) return undefined;
    if (destination && findEntry(entries, destination)?.type !== "directory") return undefined;
    if (entry.path === destination || destination.startsWith(`${entry.path}/`)) return undefined;
    const target = destination ? `${destination}/${entry.name}` : entry.name;
    if (target === entry.path || findEntry(entries, target)) return undefined;
    return target;
}

export function toggleSet(current: Set<string>, path: string) {
    const next = new Set(current);
    if (next.has(path)) next.delete(path);
    else next.add(path);
    return next;
}

export function countEntries(entries: WorkspaceEntry[]): number {
    return entries.reduce((total, entry) => total + 1 + countEntries(entry.children ?? []), 0);
}

export function collectJsonFiles(entries: WorkspaceEntry[]): WorkspaceEntry[] {
    return entries.flatMap(entry => entry.type === "directory"
        ? collectJsonFiles(entry.children ?? [])
        : entry.path.toLowerCase().endsWith(".json") ? [entry] : []);
}
