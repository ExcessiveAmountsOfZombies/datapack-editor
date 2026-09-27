export interface AdvancementFile {
    path: string;
    content: string;
}

export interface AdvancementDisplay {
    title?: unknown;
    description?: unknown;
    icon?: { id?: string; item?: string; components?: Record<string, unknown> };
    background?: string;
    frame?: string;
    show_toast?: boolean;
    announce_to_chat?: boolean;
    hidden?: boolean;
    x?: number;
    y?: number;
}

export interface AdvancementNode {
    id: string;
    path: string;
    parent?: string;
    display?: AdvancementDisplay;
}

export interface AdvancementTree {
    root: AdvancementNode;
    nodes: AdvancementNode[];
}

export function advancementId(path: string): string | undefined {
    const match = /(?:^|\/)data\/([^/]+)\/advancements?\/(.+)\.json$/i.exec(path);
    return match ? `${match[1]}:${match[2]}` : undefined;
}

export function isAdvancementPath(path: string | undefined): boolean {
    return Boolean(path && advancementId(path));
}

export function parseAdvancement(file: AdvancementFile): AdvancementNode | undefined {
    const id = advancementId(file.path);
    if (!id) return undefined;
    try {
        const value: unknown = JSON.parse(file.content);
        if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
        const data = value as Record<string, unknown>;
        const display = data.display && typeof data.display === "object" && !Array.isArray(data.display)
            ? data.display as AdvancementDisplay : undefined;
        return {
            id, path: file.path, parent: typeof data.parent === "string" ? normalizeId(data.parent) : undefined,
            display
        };
    } catch {
        return undefined;
    }
}

function normalizeId(id: string): string {
    return id.includes(":") ? id : `minecraft:${id}`;
}

export function buildAdvancementTrees(files: AdvancementFile[], activePath?: string): AdvancementTree[] {
    const nodes = new Map<string, AdvancementNode>();
    for (const file of files) {
        const node = parseAdvancement(file);
        if (node && !nodes.has(node.id)) nodes.set(node.id, node);
    }
    // Prefer definitions from the pack being inspected, then workspace overrides.
    const activePack = activePath?.includes("/data/") ? activePath.slice(0, activePath.indexOf("/data/")) : undefined;
    for (const file of files.filter(file => activePack && file.path.startsWith(`${activePack}/data/`))) {
        const node = parseAdvancement(file);
        if (node) nodes.set(node.id, node);
    }
    for (const file of files.filter(file => file.path.startsWith("data/"))) {
        const node = parseAdvancement(file);
        if (node) nodes.set(node.id, node);
    }
    if (activePath) {
        const active = files.find(file => file.path === activePath);
        const node = active && parseAdvancement(active);
        if (node) nodes.set(node.id, node);
    }

    const roots = new Map<string, AdvancementTree>();
    for (const node of nodes.values()) {
        let root = node;
        const visited = new Set<string>([node.id]);
        while (root.parent && nodes.has(root.parent) && !visited.has(root.parent)) {
            visited.add(root.parent);
            root = nodes.get(root.parent)!;
        }
        if (!root.display) continue;
        const tree = roots.get(root.id) ?? {root, nodes: []};
        tree.nodes.push(node);
        roots.set(root.id, tree);
    }
    return [...roots.values()]
        .filter(tree => tree.root.display)
        .sort((left, right) => left.root.id.localeCompare(right.root.id));
}

export interface PositionedAdvancement extends AdvancementNode {
    x: number;
    y: number;
    visibleParent?: string;
}

export function layoutAdvancementTree(tree: AdvancementTree): PositionedAdvancement[] {
    const displayed = tree.nodes.filter(node => node.display);
    const byId = new Map(tree.nodes.map(node => [node.id, node]));
    const children = new Map<string, AdvancementNode[]>();
    const parents = new Map<string, string>();
    for (const node of displayed) {
        if (node.id === tree.root.id) continue;
        let parent = node.parent ? byId.get(node.parent) : undefined;
        const visited = new Set<string>([node.id]);
        while (parent && !parent.display && !visited.has(parent.id)) {
            visited.add(parent.id);
            parent = parent.parent ? byId.get(parent.parent) : undefined;
        }
        if (!parent?.display || visited.has(parent.id)) continue;
        parents.set(node.id, parent.id);
        children.set(parent.id, [...(children.get(parent.id) ?? []), node]);
    }
    for (const siblings of children.values()) siblings.sort((a, b) => a.id.localeCompare(b.id));
    const positions = new Map<string, { x: number; y: number }>();
    const visiting = new Set<string>();
    let nextRow = 0;
    const place = (node: AdvancementNode, depth: number): number => {
        if (visiting.has(node.id)) return nextRow++;
        visiting.add(node.id);
        const childRows = (children.get(node.id) ?? []).map(child => place(child, depth + 1));
        const row = childRows.length ? (childRows[0] + childRows.at(-1)!) / 2 : nextRow++;
        positions.set(node.id, {x: depth, y: row});
        return row;
    };
    place(tree.root, 0);
    return displayed.map(node => ({
        ...node, ...(positions.get(node.id) ?? {x: 0, y: nextRow++}),
        visibleParent: parents.get(node.id)
    }));
}

export function advancementText(value: unknown, language: Record<string, string>): string {
    if (typeof value === "string") return value;
    if (!value || typeof value !== "object" || Array.isArray(value)) return "";
    const text = value as Record<string, unknown>;
    let result = typeof text.text === "string" ? text.text : "";
    if (typeof text.translate === "string") {
        result = language[text.translate] ?? text.translate;
        if (Array.isArray(text.with)) {
            let index = 0;
            const argumentsList = text.with as unknown[];
            result = result.replace(/%(?:(\d+)\$)?s/g, (_, number: string | undefined) =>
                advancementText(argumentsList[number ? Number(number) - 1 : index++], language));
        }
    }
    if (Array.isArray(text.extra)) result += text.extra.map(part => advancementText(part, language)).join("");
    return result;
}
