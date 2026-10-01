export interface FeatureBlock {
    id: string;
    properties?: Record<string, string>;
    pos: [number, number, number];
}

export interface FeatureSimulation {
    seed: string;
    placed: boolean;
    changedBlocks: number;
    blocks: FeatureBlock[];
}

export interface FeatureSettings {
    seed: string;
}

export function featureResource(path: string | undefined): {kind: string; id: string} | undefined {
    const match = path?.replaceAll("\\", "/").match(/(?:^|\/)data\/([^/]+)\/worldgen\/(configured_feature|placed_feature)\/(.+)\.json$/);
    return match ? {kind: match[2], id: `${match[1]}:${match[3]}`} : undefined;
}

export function isConfiguredFeaturePath(path: string | undefined): boolean {
    return featureResource(path)?.kind === "configured_feature";
}

export function configuredFeatureError(content: string): string | undefined {
    try {
        const value = JSON.parse(content);
        if (!value || typeof value !== "object" || Array.isArray(value)
            || typeof value.type !== "string" || !value.config || typeof value.config !== "object" || Array.isArray(value.config)) {
            return "Expected a configured feature with a type and config.";
        }
    } catch {
        return "Fix the JSON to generate this feature.";
    }
    return undefined;
}

export function featureSeedError(seed: string): string | undefined {
    try {
        if (!/^-?\d+$/.test(seed)) throw new Error();
        const value = BigInt(seed);
        if (value < -(1n << 63n) || value >= (1n << 63n)) throw new Error();
    } catch {
        return "Seed must be a whole number between in the LONG range";
    }
    return undefined;
}

export function randomFeatureSeed(): string {
    const values = crypto.getRandomValues(new Uint32Array(2));
    return BigInt.asIntN(64, (BigInt(values[0]) << 32n) | BigInt(values[1])).toString();
}
