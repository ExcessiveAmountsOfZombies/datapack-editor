import {h} from "preact";
import {registerRecipePreview} from "./recipePreviewRegistry.ts";
import type {RecipePreviewComponentProps} from "./recipePreviewRegistry.ts";
import {VanillaRecipePreview, type VanillaRecipeLayout} from "./VanillaRecipePreview.tsx";

function vanilla(layout: VanillaRecipeLayout) {
    return (props: RecipePreviewComponentProps) => h(VanillaRecipePreview, {...props, layout});
}

registerRecipePreview({
    id: "minecraft:crafting_shaped",
    label: "Crafting (shaped)",
    component: vanilla({kind: "shaped", texture: "crafting_table.png"}),
    create: () => ({type: "minecraft:crafting_shaped", category: "misc", pattern: ["A"],
        key: {A: {item: "minecraft:stone"}}, result: {id: "minecraft:stone"}})
});

registerRecipePreview({
    id: "minecraft:crafting_shapeless",
    label: "Crafting (shapeless)",
    component: vanilla({kind: "shapeless", texture: "crafting_table.png",
        notice: "Shapeless ingredients fill the grid in list order."}),
    create: () => ({type: "minecraft:crafting_shapeless", category: "misc",
        ingredients: [{item: "minecraft:stone"}], result: {id: "minecraft:stone"}})
});

function registerCooking(id: string, label: string, texture: string, cookingTime: number, notice?: string) {
    registerRecipePreview({id, label, component: vanilla({kind: "cooking", texture, cookingTime, notice}),
        create: () => ({type: id, category: "misc", ingredient: {item: "minecraft:stone"},
            result: {id: "minecraft:stone"}, experience: 0, cookingtime: cookingTime})});
}

registerCooking("minecraft:smelting", "Smelting", "furnace.png", 200);
registerCooking("minecraft:blasting", "Blasting", "blast_furnace.png", 100);
registerCooking("minecraft:smoking", "Smoking", "smoker.png", 100);
registerCooking("minecraft:campfire_cooking", "Campfire cooking", "furnace.png", 600);

registerRecipePreview({
    id: "minecraft:stonecutting",
    label: "Stonecutting",
    component: vanilla({kind: "stonecutting", texture: "stonecutter.png"}),
    create: () => ({type: "minecraft:stonecutting", ingredient: {item: "minecraft:stone"}, result: {id: "minecraft:stone"}})
});

registerRecipePreview({
    id: "minecraft:smithing_transform",
    label: "Smithing (upgrade)",
    component: vanilla({kind: "smithing_transform", texture: "smithing.png"}),
    create: () => ({type: "minecraft:smithing_transform", template: {item: "minecraft:netherite_upgrade_smithing_template"},
        base: {item: "minecraft:diamond_sword"}, addition: {item: "minecraft:netherite_ingot"}, result: {id: "minecraft:netherite_sword"}})
});

registerRecipePreview({
    id: "minecraft:smithing_trim",
    label: "Smithing (trim)",
    component: vanilla({kind: "smithing_trim", texture: "smithing.png"}),
    create: () => ({type: "minecraft:smithing_trim", template: {item: "minecraft:sentry_armor_trim_smithing_template"},
        base: {item: "minecraft:iron_chestplate"}, addition: {item: "minecraft:iron_ingot"}})
});
