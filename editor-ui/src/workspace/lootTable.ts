export interface ChestStack {
    id: string;
    count: number;
    components?: Record<string, unknown>;
}

export interface ChestSimulation {
    seed: string;
    slots: Array<ChestStack | null>;
}

export function isChestLootTablePath(path: string | undefined): boolean {
    return Boolean(path && /(?:^|\/)data\/[^/]+\/loot_tables?\/.*\.json$/i.test(path));
}

export function chestLootError(content: string): string | undefined {
    let table: unknown;
    try {
        table = JSON.parse(content);
    } catch {
        return "Fix the JSON to simulate this loot table.";
    }
    if (table === null || typeof table !== "object" || Array.isArray(table)) return "This file is not a loot table.";
    const type = (table as Record<string, unknown>).type;
    if (type !== "minecraft:chest" && type !== "chest") return "Simulation is available for chest loot tables.";
    return undefined;
}
