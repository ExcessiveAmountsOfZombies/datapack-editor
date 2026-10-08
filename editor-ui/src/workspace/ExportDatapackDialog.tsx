import {useLayoutEffect, useRef, useState} from "preact/hooks";
import {CloseIcon} from "./Codicon";

export interface DatapackExportSettings {
    name: string;
    description: string;
}

export function ExportDatapackDialog({unsavedPaths, onExport, onCancel}: {
    unsavedPaths: string[];
    onExport: (settings: DatapackExportSettings, saveAll: boolean) => Promise<void>;
    onCancel: () => void;
}) {
    const dialog = useRef<HTMLDivElement>(null);
    const nameInput = useRef<HTMLInputElement>(null);
    const [name, setName] = useState("my-datapack");
    const [description, setDescription] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string>();
    const [confirmSave, setConfirmSave] = useState(false);

    useLayoutEffect(() => {
        const previous = document.activeElement as HTMLElement | null;
        nameInput.current?.focus();
        nameInput.current?.select();
        return () => previous?.focus();
    }, []);

    const inputClass = "w-full border border-white/15 bg-[#3c3c3c] px-2 py-1.5 text-xs text-zinc-100 outline-none focus:border-violet-400";
    return <div ref={dialog} role="dialog" aria-modal="true" aria-labelledby="export-datapack-title"
                aria-busy={busy} class="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4"
                onClick={() => { if (!busy) onCancel(); }}
                onKeyDown={event => {
                    if (event.key === "Escape") {
                        event.preventDefault();
                        event.stopPropagation();
                        if (!busy) onCancel();
                    }
                    if (event.key === "Tab") {
                        const controls = Array.from(dialog.current?.querySelectorAll<HTMLElement>(
                            'input:not([disabled]), textarea:not([disabled]), button:not([disabled])') ?? []);
                        const target = event.shiftKey ? controls.at(-1) : controls[0];
                        if (document.activeElement === (event.shiftKey ? controls[0] : controls.at(-1))) {
                            event.preventDefault();
                            target?.focus();
                        }
                    }
                }}>
        <section class="max-h-[90vh] w-full max-w-lg overflow-y-auto border border-white/20 bg-[#252526] p-5 shadow-2xl"
                 onClick={event => event.stopPropagation()}>
            <div class="mb-4 flex items-center justify-between text-zinc-100">
                <h2 id="export-datapack-title" class="text-sm font-semibold">Export Datapack</h2>
                <button type="button" aria-label="Close export dialog" disabled={busy} onClick={onCancel}
                        class="p-1 hover:bg-white/10 disabled:opacity-40"><CloseIcon/></button>
            </div>
            <form class="space-y-4" onSubmit={async event => {
                event.preventDefault();
                if (busy || !name.trim()) return;
                if (unsavedPaths.length > 0 && !confirmSave) {
                    setConfirmSave(true);
                    setError(undefined);
                    return;
                }
                setBusy(true);
                setError(undefined);
                try {
                    await onExport({name: name.trim(), description}, confirmSave);
                } catch (reason) {
                    setError(reason instanceof Error ? reason.message : String(reason));
                    setBusy(false);
                }
            }}>
                <label class="block space-y-1 text-xs text-zinc-300">
                    <span>Datapack name</span>
                    <input ref={nameInput} value={name} required disabled={busy}
                           class={inputClass} onInput={event => setName(event.currentTarget.value)}
                           aria-describedby="export-name-help"/>
                    <span id="export-name-help" class="block text-[11px] text-zinc-400">The ZIP filename. A .zip extension is added automatically.</span>
                </label>
                <label class="block space-y-1 text-xs text-zinc-300">
                    <span>Description</span>
                    <textarea value={description} rows={3} disabled={busy} class={`${inputClass} resize-y`}
                              onInput={event => setDescription(event.currentTarget.value)}/>
                </label>
                <div class="grid grid-cols-2 gap-3">
                    <label class="block space-y-1 text-xs text-zinc-300">
                        <span>Minimum version</span>
                        <input type="text" value="107.1" readOnly class={`${inputClass} text-zinc-400`}/>
                    </label>
                    <label class="block space-y-1 text-xs text-zinc-300">
                        <span>Maximum version</span>
                        <input type="text" value="107.1" readOnly class={`${inputClass} text-zinc-400`}/>
                    </label>
                </div>
                <p class="text-[11px] leading-5 text-zinc-400">Exports saved workspace files to
                    <span class="text-zinc-200"> workspace-exported-datapacks </span>in the game directory.</p>
                {confirmSave && unsavedPaths.length > 0 && <div role="status"
                    class="border border-amber-400/30 bg-amber-400/5 p-3 text-xs text-zinc-200">
                    <p class="mb-2 font-semibold">Unsaved changes</p>
                    <p>Save all {unsavedPaths.length} unsaved workspace {unsavedPaths.length === 1 ? "file" : "files"} before exporting?</p>
                    <ul class="max-h-24 overflow-y-auto pl-4 text-[11px] text-zinc-400">
                        {unsavedPaths.map(path => <li key={path} class="break-words">{path}</li>)}
                    </ul>
                </div>}
                {error && <p role="alert" class="text-xs text-red-300">{error}</p>}
                <div class="flex justify-end gap-2">
                    <button type="button" disabled={busy} onClick={onCancel}
                            class="border border-white/15 px-3 py-1.5 text-xs text-zinc-200 hover:bg-white/10 disabled:opacity-40">Cancel</button>
                    <button type="submit" disabled={busy || !name.trim()}
                            class="bg-violet-600 px-3 py-1.5 text-xs text-white hover:bg-violet-500 disabled:opacity-40">
                        {busy ? "Exporting…" : confirmSave && unsavedPaths.length > 0 ? "Save All and Export" : "Export Datapack"}
                    </button>
                </div>
            </form>
        </section>
    </div>;
}
