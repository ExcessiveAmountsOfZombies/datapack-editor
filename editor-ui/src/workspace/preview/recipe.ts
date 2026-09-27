export const recipeTypes = [
    {id: "minecraft:crafting_shaped", label: "Crafting (shaped)", texture: "crafting_table.png"},
    {id: "minecraft:crafting_shapeless", label: "Crafting (shapeless)", texture: "crafting_table.png"},
    {id: "minecraft:smelting", label: "Smelting", texture: "furnace.png"},
    {id: "minecraft:blasting", label: "Blasting", texture: "blast_furnace.png"},
    {id: "minecraft:smoking", label: "Smoking", texture: "smoker.png"},
    {id: "minecraft:campfire_cooking", label: "Campfire cooking", texture: "furnace.png"},
    {id: "minecraft:stonecutting", label: "Stonecutting", texture: "stonecutter.png"},
    {id: "minecraft:smithing_transform", label: "Smithing (upgrade)", texture: "smithing.png"},
    {id: "minecraft:smithing_trim", label: "Smithing (trim)", texture: "smithing.png"}
] as const;

export type RecipeType = typeof recipeTypes[number]["id"];
export type RecipeSlot = "result" | "ingredient" | "template" | "base" | "addition" | `grid${number}`;
export type RecipeObject = Record<string, unknown>;

export function isRecipePath(path: string | undefined): boolean {
    return Boolean(path && /(?:^|\/)data\/[^/]+\/recipes?\/.*\.json$/i.test(path));
}

export function recipeType(recipe: RecipeObject | undefined): RecipeType | undefined {
    return recipeTypes.find(type => type.id === recipe?.type)?.id;
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

export function newRecipe(type: RecipeType): RecipeObject {
    const recipe: RecipeObject = {type};
    if (type === "minecraft:crafting_shaped") {
        recipe.category = "misc";
        recipe.pattern = ["A"];
        recipe.key = {A: "minecraft:stone"};
        recipe.result = {id: "minecraft:stone"};
    } else if (type === "minecraft:crafting_shapeless") {
        recipe.category = "misc";
        recipe.ingredients = ["minecraft:stone"];
        recipe.result = {id: "minecraft:stone"};
    } else if (["minecraft:smelting", "minecraft:blasting", "minecraft:smoking", "minecraft:campfire_cooking"].includes(type)) {
        recipe.category = "misc";
        recipe.ingredient = "minecraft:stone";
        recipe.result = {id: "minecraft:stone"};
        recipe.experience = 0;
        recipe.cookingtime = type === "minecraft:smelting" ? 200 : type === "minecraft:campfire_cooking" ? 600 : 100;
    } else if (type === "minecraft:stonecutting") {
        recipe.ingredient = "minecraft:stone";
        recipe.result = {id: "minecraft:stone"};
    } else if (type === "minecraft:smithing_transform") {
        recipe.template = "minecraft:netherite_upgrade_smithing_template";
        recipe.base = "minecraft:diamond_sword";
        recipe.addition = "minecraft:netherite_ingot";
        recipe.result = {id: "minecraft:netherite_sword"};
    } else {
        recipe.template = "minecraft:sentry_armor_trim_smithing_template";
        recipe.base = "minecraft:iron_chestplate";
        recipe.addition = "minecraft:iron_ingot";
        recipe.pattern = "minecraft:sentry";
    }
    return recipe;
}

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
    if (slot.startsWith("grid")) {
        const index = Number(slot.slice(4));
        if (recipe.type === "minecraft:crafting_shapeless") {
            return ingredientId((recipe.ingredients as unknown[] | undefined)?.[index]);
        }
        const pattern = recipe.pattern as string[] | undefined;
        const symbol = pattern?.[Math.floor(index / 3)]?.[index % 3];
        return symbol && symbol !== " " ? ingredientId((recipe.key as RecipeObject | undefined)?.[symbol]) : undefined;
    }
    return ingredientId(recipe[slot]);
}

export function resultCount(recipe: RecipeObject): number {
    const result = recipe.result;
    return result && typeof result === "object" && typeof (result as RecipeObject).count === "number"
        ? (result as RecipeObject).count as number : 1;
}

export function setSlot(recipe: RecipeObject, slot: RecipeSlot, id: string | undefined): RecipeObject {
    const next = {...recipe};
    if (slot === "result") {
        if (id) next.result = {...(typeof recipe.result === "object" ? recipe.result as RecipeObject : {}), id};
        else delete next.result;
    } else if (slot.startsWith("grid")) {
        const index = Number(slot.slice(4));
        if (recipe.type === "minecraft:crafting_shapeless") {
            const ingredients = Array.isArray(recipe.ingredients) ? [...recipe.ingredients] : [];
            ingredients[index] = id;
            next.ingredients = ingredients.filter(Boolean);
        } else {
            const values = Array.from({length: 9}, (_, at) => slotItem(recipe, `grid${at}`));
            values[index] = id;
            const key: RecipeObject = {};
            const symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
            const ids = [...new Set(values.filter((item): item is string => Boolean(item)))];
            ids.forEach((item, at) => { key[symbols[at]] = item; });
            next.key = key;
            next.pattern = [0, 1, 2].map(row => values.slice(row * 3, row * 3 + 3)
                .map(item => item ? symbols[ids.indexOf(item)] : " ").join(""));
        }
    } else if (id) next[slot] = id;
    else delete next[slot];
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
