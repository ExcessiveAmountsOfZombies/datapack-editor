import type {RecipeObject} from "./recipeDocument.ts";

export type RecipeSlot = "result" | "ingredient" | "template" | "base" | "addition" | `grid${number}`;

function ingredientId(value: unknown): string | undefined {
    if (typeof value === "string") return value;
    if (value && typeof value === "object" && !Array.isArray(value)) {
        const object = value as RecipeObject;
        if (typeof object.item === "string") return object.item;
        if (typeof object.tag === "string") return `#${object.tag}`;
    }
    return undefined;
}

export function slotItem(recipe: RecipeObject, slot: RecipeSlot): string | undefined {
    if (slot === "result") {
        const result = recipe.result;
        if (typeof result === "string") return result;
        const id = result && typeof result === "object" ? (result as RecipeObject).id : undefined;
        return typeof id === "string" ? id : undefined;
    }
    return ingredientId(slotValue(recipe, slot));
}

export function slotValue(recipe: RecipeObject, slot: RecipeSlot): unknown {
    if (slot.startsWith("grid")) {
        const index = Number(slot.slice(4));
        if (recipe.type === "minecraft:crafting_shapeless") {
            return Array.isArray(recipe.ingredients) ? recipe.ingredients[index] : undefined;
        }
        const row = Array.isArray(recipe.pattern) ? recipe.pattern[Math.floor(index / 3)] : undefined;
        const symbol = typeof row === "string" ? row[index % 3] : undefined;
        return symbol && symbol !== " " ? (recipe.key as RecipeObject | undefined)?.[symbol] : undefined;
    }
    return recipe[slot];
}

export function resultCount(recipe: RecipeObject): number {
    const result = recipe.result;
    return result && typeof result === "object" && typeof (result as RecipeObject).count === "number"
        ? (result as RecipeObject).count as number : 1;
}

export function setSlot(recipe: RecipeObject, slot: RecipeSlot, id: unknown): RecipeObject {
    const next = {...recipe};
    if (slot === "result") {
        if (id) next.result = {...(typeof recipe.result === "object" ? recipe.result as RecipeObject : {}), id};
        else delete next.result;
    } else {
        if (typeof id === "string") id = id.startsWith("#") ? {tag: id.slice(1)} : {item: id};
        if (slot.startsWith("grid")) {
            const index = Number(slot.slice(4));
            if (recipe.type === "minecraft:crafting_shapeless") {
                const ingredients = Array.isArray(recipe.ingredients) ? [...recipe.ingredients] : [];
                ingredients[index] = id;
                next.ingredients = ingredients.filter(Boolean);
            } else {
                const values = Array.from({length: 9}, (_, at) => slotValue(recipe, `grid${at}`));
                values[index] = id;
                const key: RecipeObject = {};
                const symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                const ids = [...new Set(values.filter(item => item != null).map(item => JSON.stringify(item)))];
                ids.forEach((item, at) => { key[symbols[at]] = JSON.parse(item); });
                next.key = key;
                next.pattern = [0, 1, 2].map(row => values.slice(row * 3, row * 3 + 3)
                    .map(item => item != null ? symbols[ids.indexOf(JSON.stringify(item))] : " ").join(""));
            }
        } else if (id) next[slot] = id;
        else delete next[slot];
    }
    return next;
}

export function setResultCount(recipe: RecipeObject, count: number): RecipeObject {
    const result = recipe.result;
    if (!result || typeof result !== "object") return recipe;
    const nextResult = {...result as RecipeObject};
    if (count === 1) delete nextResult.count;
    else nextResult.count = count;
    return {...recipe, result: nextResult};
}
