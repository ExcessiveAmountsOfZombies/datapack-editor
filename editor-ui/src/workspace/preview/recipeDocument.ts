export type RecipeObject = Record<string, unknown>;

export function isRecipePath(path: string | undefined): boolean {
    return Boolean(path && /(?:^|\/)data\/[^/]+\/recipes?\/.*\.json$/i.test(path));
}

export function parseRecipe(content: string | undefined): RecipeObject | undefined {
    if (content === undefined) return undefined;
    try {
        const value: unknown = JSON.parse(content);
        return value && typeof value === "object" && !Array.isArray(value) ? value as RecipeObject : undefined;
    } catch {
        return undefined;
    }
}
