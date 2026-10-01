import {useEffect, useState} from "preact/hooks";
import {FeatureScene} from "./FeatureScene";
import {configuredFeatureError, featureSeedError, randomFeatureSeed, type FeatureSettings, type FeatureSimulation} from "./configuredFeature";

interface ConfiguredFeaturePreviewProps {
    content: string;
    referenceRevision: number;
    simulate: (content: string, settings: FeatureSettings, signal: AbortSignal) => Promise<FeatureSimulation>;
}

export function ConfiguredFeaturePreview({content, referenceRevision, simulate}: ConfiguredFeaturePreviewProps) {
    const [seed, setSeed] = useState("1");
    const [simulation, setSimulation] = useState<FeatureSimulation>();
    const [error, setError] = useState<string>();
    const [loading, setLoading] = useState(false);
    const inputError = configuredFeatureError(content) ?? featureSeedError(seed);

    useEffect(() => {
        setError(undefined);
        setLoading(!inputError);
        if (inputError) return;
        const controller = new AbortController();
        const timer = window.setTimeout(() => {
            void simulate(content, {seed}, controller.signal).then(result => {
                if (controller.signal.aborted) return;
                setSimulation(result);
                setLoading(false);
            }).catch(reason => {
                if (controller.signal.aborted) return;
                setError(reason instanceof Error ? reason.message : String(reason));
                setLoading(false);
            });
        }, 400);
        return () => { controller.abort(); window.clearTimeout(timer); };
    }, [content, seed, inputError, referenceRevision, simulate]);

    return <div class="space-y-3 text-xs text-zinc-300">
        <p class="font-semibold text-zinc-100">Configured feature</p>
        <div class="flex items-end gap-2">
            <label class="min-w-0 flex-1">Seed
                <input aria-label="Feature seed" value={seed} onInput={event => setSeed(event.currentTarget.value)}
                       class="mt-1 w-full rounded border border-white/15 bg-[#252526] px-2 py-1.5"/>
            </label>
            <button type="button" class="rounded bg-violet-700 px-3 py-1.5 hover:bg-violet-600"
                    onClick={() => setSeed(randomFeatureSeed())}>Reroll</button>
        </div>
        <FeatureScene blocks={simulation?.blocks} loading={loading}/>
        {(inputError || error) && <p role="alert" class="text-amber-300">{inputError ?? error}</p>}
        {simulation && <>
            <p class="text-zinc-400">{simulation.changedBlocks} changed blocks · Seed {simulation.seed}</p>
            {!simulation.placed && <p class="text-amber-300">Minecraft could not place this feature. Try another seed.</p>}
            {simulation.placed && !simulation.changedBlocks && <p class="text-zinc-400">This generation produced no block changes.</p>}
        </>}
    </div>;
}
