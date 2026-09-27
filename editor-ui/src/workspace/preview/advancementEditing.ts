export interface NewAdvancement {
    namespace: string;
    name: string;
    title: string;
    description: string;
    icon: string;
    parent?: string;
}

export type AdvancementDocument = Record<string, unknown>;

export function workspaceAdvancementPath(namespace: string, name: string): string {
    const normalizedNamespace = namespace.trim();
    const normalizedName = name.trim().replace(/\.json$/i, "");
    if (!/^[a-z0-9_.-]+$/.test(normalizedNamespace) || normalizedNamespace === "." || normalizedNamespace === "..") {
        throw new Error("Use a lowercase namespace with letters, numbers, underscores, periods, or hyphens.");
    }
    const segments = normalizedName.split("/");
    if (!segments.length || segments.some(segment => !/^[a-z0-9_.-]+$/.test(segment)
        || segment === "." || segment === "..")) {
        throw new Error("Enter a lowercase advancement path such as story/new_goal.");
    }
    return `data/${normalizedNamespace}/advancement/${normalizedName}.json`;
}

export function newAdvancementContent(details: NewAdvancement): string {
    const path = workspaceAdvancementPath(details.namespace, details.name);
    const icon = details.icon.trim();
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(icon)) {
        throw new Error("Enter an icon item ID such as minecraft:book.");
    }
    const parent = details.parent?.trim();
    if (parent && !/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(parent)) {
        throw new Error("Enter a parent advancement ID such as minecraft:story/root.");
    }
    const name = path.split("/").at(-1)!.replace(/\.json$/, "").replaceAll("_", " ");
    const document: AdvancementDocument = {
        ...(parent ? {parent} : {}),
        display: {
            icon: {id: icon},
            title: details.title.trim() || name,
            description: details.description.trim(),
            frame: "task",
            show_toast: true,
            announce_to_chat: true,
            hidden: false,
            ...(!parent ? {background: "minecraft:gui/advancements/backgrounds/stone"} : {})
        },
        criteria: {created: {trigger: "minecraft:impossible"}},
        requirements: [["created"]]
    };
    return `${JSON.stringify(document, null, 2)}\n`;
}

export function readAdvancementDocument(content: string | undefined): AdvancementDocument | undefined {
    if (content === undefined) return undefined;
    try {
        const value: unknown = JSON.parse(content);
        return value && typeof value === "object" && !Array.isArray(value) ? value as AdvancementDocument : undefined;
    } catch {
        return undefined;
    }
}

export function editAdvancement(content: string, change: (document: AdvancementDocument) => void): string | undefined {
    const document = readAdvancementDocument(content);
    if (!document) return undefined;
    change(document);
    return `${JSON.stringify(document, null, 2)}\n`;
}

export function componentInput(value: unknown): string {
    if (typeof value === "string") return value;
    if (!value || typeof value !== "object" || Array.isArray(value)) return "";
    const component = value as Record<string, unknown>;
    return typeof component.translate === "string" ? component.translate
        : typeof component.text === "string" ? component.text : "";
}

export function changedComponent(value: unknown, text: string): unknown {
    if (value && typeof value === "object" && !Array.isArray(value)) {
        const component = value as Record<string, unknown>;
        if (typeof component.translate === "string") return {...component, translate: text};
        if (typeof component.text === "string") return {...component, text};
    }
    return text;
}
