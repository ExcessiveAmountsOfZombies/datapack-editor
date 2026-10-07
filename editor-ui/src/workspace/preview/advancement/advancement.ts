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
    const priorities = new Map<string, number>();
    const activePack = activePath?.includes("/data/") ? activePath.slice(0, activePath.indexOf("/data/")) : undefined;
    for (const file of files) {
        const node = parseAdvancement(file);
        if (!node) continue;
        // Parse once, preserving active file > workspace > inspected pack > first loaded definition.
        const priority = file.path === activePath ? 3 : file.path.startsWith("data/") ? 2
            : activePack && file.path.startsWith(`${activePack}/data/`) ? 1 : 0;
        const previous = priorities.get(node.id) ?? -1;
        if (priority > previous || (priority > 0 && priority === previous)) {
            nodes.set(node.id, node);
            priorities.set(node.id, priority);
        }
    }

    const roots = new Map<string, AdvancementTree>();
    const rootById = new Map<string, AdvancementNode | undefined>();
    for (const node of nodes.values()) {
        // Memoize entire parent chains. Deep trees must not walk every ancestor for every node.
        let current: AdvancementNode | undefined = node;
        let root: AdvancementNode | undefined;
        const chain: AdvancementNode[] = [];
        const visited = new Set<string>();
        while (current) {
            if (rootById.has(current.id)) {
                root = rootById.get(current.id);
                break;
            }
            if (visited.has(current.id)) break; // A parent cycle cannot form a valid tree.
            visited.add(current.id);
            chain.push(current);
            const parent: AdvancementNode | undefined = current.parent ? nodes.get(current.parent) : undefined;
            if (!parent) {
                root = current;
                break;
            }
            current = parent;
        }
        for (const ancestor of chain) rootById.set(ancestor.id, root);
        if (!root?.display) continue;
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
    const visibleAncestors = new Map<string, AdvancementNode | undefined>();
    for (const node of displayed) {
        if (node.id === tree.root.id) continue;
        let parent = node.parent ? byId.get(node.parent) : undefined;
        const visited = new Set<string>([node.id]);
        const hiddenChain: string[] = [];
        while (parent && !parent.display && !visited.has(parent.id)) {
            if (visibleAncestors.has(parent.id)) {
                parent = visibleAncestors.get(parent.id);
                break;
            }
            visited.add(parent.id);
            hiddenChain.push(parent.id);
            parent = parent.parent ? byId.get(parent.parent) : undefined;
        }
        for (const id of hiddenChain) visibleAncestors.set(id, parent);
        if (!parent?.display || visited.has(parent.id)) continue;
        parents.set(node.id, parent.id);
        const siblings = children.get(parent.id) ?? [];
        siblings.push(node);
        children.set(parent.id, siblings);
    }
    for (const siblings of children.values()) siblings.sort((a, b) => a.id.localeCompare(b.id));
    const positions = new Map<string, { x: number; y: number }>();
    const visiting = new Set<string>();
    let nextRow = 0;
    const stack = [{node: tree.root, depth: 0, finish: false}];
    while (stack.length) {
        const {node, depth, finish} = stack.pop()!;
        const siblings = children.get(node.id) ?? [];
        if (!finish) {
            if (visiting.has(node.id)) continue;
            visiting.add(node.id);
            stack.push({node, depth, finish: true});
            for (let index = siblings.length - 1; index >= 0; index--) {
                stack.push({node: siblings[index], depth: depth + 1, finish: false});
            }
        } else {
            const first = siblings.length ? positions.get(siblings[0].id) : undefined;
            const last = siblings.length ? positions.get(siblings.at(-1)!.id) : undefined;
            const row = first && last ? (first.y + last.y) / 2 : nextRow++;
            positions.set(node.id, {x: depth, y: row});
        }
    }
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
