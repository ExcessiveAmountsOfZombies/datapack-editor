import type {ComponentType} from "preact";
import type {RecipeObject} from "./recipeDocument.ts";

export interface RecipePreviewComponentProps {
    path?: string;
    content: string;
    recipe: RecipeObject;
    readOnly: boolean;
    onContentChange: (content: string) => void;
}

export interface RecipePreviewRegistration {
    id: string;
    label: string;
    component: ComponentType<RecipePreviewComponentProps>;
    create?: () => RecipeObject; /** Creates default JSON for a new recipe. Omit to support previewing existing recipes only. */
}

const previews = new Map<string, Readonly<RecipePreviewRegistration>>();

export function registerRecipePreview(registration: RecipePreviewRegistration): void {
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(registration.id)) {
        throw new Error(`Invalid recipe serializer ID: ${registration.id}`);
    }
    if (previews.has(registration.id)) throw new Error(`Recipe preview already registered: ${registration.id}`);
    previews.set(registration.id, Object.freeze({...registration}));
}

export function getRecipePreview(id: unknown): Readonly<RecipePreviewRegistration> | undefined {
    return typeof id === "string" ? previews.get(id) : undefined;
}

export function listRecipePreviews(): Readonly<RecipePreviewRegistration>[] {
    return [...previews.values()];
}
