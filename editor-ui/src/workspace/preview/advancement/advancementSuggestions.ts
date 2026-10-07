import type {WorkspaceEntry, WorkspaceTree} from "../../model.ts";
import {advancementId, type AdvancementFile} from "./advancement.ts";
import type {AdvancementCompletions} from "./AdvancementAutocomplete.tsx";

const defaultTriggers = ["minecraft:impossible", "minecraft:inventory_changed", "minecraft:location", "minecraft:tick"];
const defaultBackgrounds = ["minecraft:gui/advancements/backgrounds/stone",
    "minecraft:gui/advancements/backgrounds/nether", "minecraft:gui/advancements/backgrounds/end",
    "minecraft:gui/advancements/backgrounds/adventure", "minecraft:gui/advancements/backgrounds/husbandry"];

function sorted(values: Iterable<string>): string[] {
    return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}

function resourceIds(entries: WorkspaceEntry[], folder: string, extension: string): string[] {
    const pattern = new RegExp(`(?:^|/)data/([^/]+)/${folder}/(.+)\\.${extension}$`);
    const result: string[] = [];
    const visit = (children: WorkspaceEntry[]) => {
        for (const entry of children) {
            if (entry.type === "directory") visit(entry.children ?? []);
            else {
                const match = pattern.exec(entry.path);
                if (match) result.push(`${match[1]}:${match[2]}`);
            }
        }
    };
    visit(entries);
    return result;
}

export function advancementCompletions(files: AdvancementFile[], items: string[],
                                       workspace?: WorkspaceTree, datapacks?: WorkspaceTree): AdvancementCompletions {
    const advancements = new Set<string>();
    const triggers = new Set(defaultTriggers);
    const backgrounds = new Set(defaultBackgrounds);
    for (const file of files) {
        const id = advancementId(file.path);
        if (id) advancements.add(id);
        try {
            const document = JSON.parse(file.content) as Record<string, unknown>;
            if (!document || typeof document !== "object") continue;
            const criteria = document.criteria;
            if (criteria && typeof criteria === "object") for (const value of Object.values(criteria)) {
                if (value && typeof value === "object" && "trigger" in value && typeof value.trigger === "string") {
                    triggers.add(value.trigger);
                }
            }
            const display = document.display;
            if (display && typeof display === "object" && "background" in display && typeof display.background === "string") {
                backgrounds.add(display.background);
            }
        } catch { /* Invalid JSON does not prevent suggestions from other files. */ }
    }
    const entries = [...(workspace?.entries ?? []), ...(datapacks?.entries ?? [])];
    return {
        advancements: sorted(advancements), items: sorted(items), triggers: sorted(triggers),
        backgrounds: sorted(backgrounds),
        recipes: sorted(resourceIds(entries, "recipes?", "json")),
        lootTables: sorted(resourceIds(entries, "loot_tables?", "json")),
        functions: sorted(resourceIds(entries, "functions?", "mcfunction"))
    };
}
