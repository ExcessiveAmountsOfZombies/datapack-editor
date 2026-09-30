import {useEffect, useMemo, useRef, useState} from "preact/hooks";
import {FileGlyph} from "../Codicon";
import {collectJsonFiles, entryKey, entrySource, type WorkspaceEntry, type WorkspaceTree} from "../model";

interface SearchViewProps {
    tree?: WorkspaceTree;
    datapacks?: WorkspaceTree;
    active: boolean;
    selectedPath?: string;
    onOpen: (entry: WorkspaceEntry) => void;
    onContextMenu: (event: MouseEvent, entry: WorkspaceEntry) => void;
}

export function SearchView({tree, datapacks, active, selectedPath, onOpen, onContextMenu}: SearchViewProps) {
    const [query, setQuery] = useState("");
    const input = useRef<HTMLInputElement>(null);
    const results = useMemo(() => {
        const needle = query.trim().toLowerCase();
        return needle ? collectJsonFiles([...(tree?.entries ?? []), ...(datapacks?.entries ?? [])])
            .filter(entry => entry.path.toLowerCase().includes(needle)) : [];
    }, [tree, datapacks, query]);

    useEffect(() => {
        if (active) input.current?.focus();
    }, [active]);

    return (
        <>
            <div class="flex h-11 shrink-0 items-center px-4 text-[11px] font-medium uppercase tracking-wide text-zinc-300">Search</div>
            <div class="px-3 pb-3">
                <input ref={input} type="search" value={query}
                       onInput={event => setQuery(event.currentTarget.value)}
                       aria-label="Search JSON filenames or paths" placeholder="Search JSON files"
                       class="w-full border border-white/15 bg-[#252526] px-2 py-1.5 text-xs text-zinc-100 outline-none focus:border-violet-400"/>
                <p class="my-2 text-xs text-zinc-500" role="status">
                    {!tree || !datapacks ? "Loading files…" : !query.trim() ? "Find JSON files by name or path." : `${results.length} ${results.length === 1 ? "file" : "files"} found`}
                </p>
            </div>
            <div class="min-h-0 flex-1 overflow-auto">
                {results.map(entry => (
                    <button key={entryKey(entry)} type="button"
                            onClick={() => onOpen(entry)} onContextMenu={event => onContextMenu(event, entry)}
                            title={entry.path} aria-label={`${entry.name} — ${entry.path}`}
                            class={`flex w-full items-start gap-2 px-3 py-2 text-left hover:bg-[#2a2d2e] ${selectedPath === entryKey(entry) ? "bg-[#37373d]" : ""}`}>
                        <FileGlyph path={entry.path}/>
                        <span class="min-w-0 flex-1">
                            <span class="block truncate text-xs text-zinc-200" title={entry.name}>{entry.name}</span>
                            <span class="block truncate text-[11px] text-zinc-500" title={entry.path}>{entry.path}</span>
                            <span class="block text-[10px] text-zinc-500">{entrySource(entry) === "datapack" ? "Datapack · Read only" : "Workspace"}</span>
                        </span>
                    </button>
                ))}
                {tree && datapacks && query.trim() && results.length === 0 && (
                    <p class="m-0 px-3 text-xs text-zinc-400">No matching JSON files.</p>
                )}
            </div>
        </>
    );
}
