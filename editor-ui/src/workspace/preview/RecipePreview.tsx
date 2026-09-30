import {useMemo} from "preact/hooks";
import {isRecipePath, parseRecipe} from "./recipeDocument";
import {getRecipePreview, listRecipePreviews} from "./recipePreviewRegistry";
import {ServerRecipePreview} from "./ServerRecipePreview";

interface RecipePreviewProps {
    path?: string;
    content?: string;
    readOnly: boolean;
    onContentChange: (content: string) => void;
}

export function RecipePreview({path, content, readOnly, onContentChange}: RecipePreviewProps) {
    const recipe = useMemo(() => parseRecipe(content), [content]);
    const type = typeof recipe?.type === "string" ? recipe.type : "";
    const registration = getRecipePreview(type);
    const Component = registration?.component;
    const choices = listRecipePreviews().filter(candidate => candidate.create);
    const write = (next: string) => { if (!readOnly) onContentChange(next); };
    const changeType = (selected: string) => {
        if (readOnly || selected === type) return;
        const create = getRecipePreview(selected)?.create;
        if (!create) return;
        if (content?.trim() && !window.confirm("Replace the current recipe with a new recipe of this type?")) return;
        write(`${JSON.stringify(create(), null, 2)}\n`);
    };

    if (!isRecipePath(path)) return null;
    return <div class="space-y-3 text-xs text-zinc-300">
        <label class="block space-y-1">
            <span class="font-medium text-zinc-400">Recipe type</span>
            <select value={type} disabled={readOnly} onChange={event => changeType(event.currentTarget.value)}
                    class="w-full rounded border border-white/15 bg-[#252526] px-2 py-1.5 text-zinc-100 disabled:opacity-50">
                <option value="" disabled>Select a recipe type…</option>
                {type && !choices.some(candidate => candidate.id === type) &&
                    <option value={type} disabled>{registration?.label ?? type}</option>}
                {choices.map(candidate => <option key={candidate.id} value={candidate.id}>{candidate.label}</option>)}
            </select>
        </label>
        {!recipe && content?.trim() && <p class="text-amber-300">Fix the JSON or select a recipe type to start a new recipe.</p>}
        {recipe && (Component
            ? <Component key={`${path}:${type}`} path={path} content={content!} recipe={recipe}
                         readOnly={readOnly} onContentChange={write}/>
            : <ServerRecipePreview key={`${path}:${type}`} content={content!}/>)}
    </div>;
}
