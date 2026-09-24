import {useEffect, useRef, useState} from "preact/hooks";
import {ExplorerGlyph, ExplorerView, useExplorerState} from "./sidebar/ExplorerView";
import {FileGlyph} from "./FileGlyph";
import {SearchGlyph, SearchView} from "./sidebar/SearchView";
import {Sidebar} from "./sidebar/Sidebar";
import {
    countEntries, datapackOverridePath, datapackPrefix, displayPath, entryKey, entrySource,
    fileName, findEntry, findFirstEditable, isDatapackKey, selectedDirectory,
    type WorkspaceEntry, type WorkspaceTree
} from "./model";
import {
    configureDataPackSchemas,
    configureRecipeSchema,
    configureTagSchemas,
    monaco,
    type JsonSchemaRegistration
} from "./monaco";

interface WorkspaceEditorProps {
    token: string;
}

interface ExplorerContextMenu {
    entry: WorkspaceEntry;
    x: number;
    y: number;
}

const apiRoot = "/api";

export function WorkspaceEditor({token}: WorkspaceEditorProps) {
    const editorHost = useRef<HTMLDivElement>(null);
    const editor = useRef<monaco.editor.IStandaloneCodeEditor>();
    const models = useRef(new Map<string, monaco.editor.ITextModel>());
    const savedContent = useRef(new Map<string, string>());
    const activePathRef = useRef<string>();
    const saveActiveRef = useRef<() => void>(() => undefined);
    const [tree, setTree] = useState<WorkspaceTree>();
    const [datapacks, setDatapacks] = useState<WorkspaceTree>();
    const [sidebar, setSidebar] = useState<string | null>("explorer");
    const explorer = useExplorerState(sidebar === "explorer");
    const [contextMenu, setContextMenu] = useState<ExplorerContextMenu>();
    const [selectedPath, setSelectedPath] = useState<string>();
    const [openPaths, setOpenPaths] = useState<string[]>([]);
    const [activePath, setActivePath] = useState<string>();
    const [revision, setRevision] = useState(0);
    const [message, setMessage] = useState("Loading workspace…");
    const [cursor, setCursor] = useState({line: 1, column: 1});

    const request = async (path: string, init?: RequestInit) => {
        const headers = new Headers(init?.headers);
        headers.set("Authorization", `Bearer ${token}`);
        const response = await fetch(`${apiRoot}${path}`, {...init, headers});
        if (!response.ok) {
            let detail = `Request failed (${response.status})`;
            try {
                detail = (await response.json()).error ?? detail;
            } catch {
                // Keep the status-based message when the response is not JSON.
            }
            throw new Error(detail);
        }
        return response;
    };

    useEffect(() => {
        let cancelled = false;
        request("/schema/recipes")
            .then(response => response.json())
            .then(schema => {
                if (!cancelled) configureRecipeSchema(schema as object);
            })
            .catch(reason => {
                if (!cancelled) setMessage(`Recipe completions unavailable: ${errorMessage(reason)}`);
            });
        request("/schema/tags")
            .then(response => response.json())
            .then(response => {
                const result = response as {schemas?: JsonSchemaRegistration[]};
                if (!cancelled) configureTagSchemas(result.schemas ?? []);
            })
            .catch(reason => {
                if (!cancelled) setMessage(`Tag completions unavailable: ${errorMessage(reason)}`);
            });
        request("/schema/datapack")
            .then(response => response.json())
            .then(response => {
                const result = response as {schemas?: JsonSchemaRegistration[]};
                if (!cancelled) configureDataPackSchemas(result.schemas ?? []);
            })
            .catch(reason => {
                if (!cancelled) setMessage(`Datapack completions unavailable: ${errorMessage(reason)}`);
            });
        return () => {
            cancelled = true;
        };
    }, [token]);

    const refreshTree = async () => {
        try {
            const [workspaceResponse, datapackResponse] = await Promise.all([
                request("/workspace"),
                request("/datapacks")
            ]);
            const nextTree = await workspaceResponse.json() as WorkspaceTree;
            const nextDatapacks = await datapackResponse.json() as WorkspaceTree;
            setTree(nextTree);
            setDatapacks(nextDatapacks);
            explorer.initialize(nextTree.entries);
            setMessage(`${countEntries(nextTree.entries)} workspace entries · ${nextDatapacks.fileCount ?? 0} data-pack JSON files`);
            return nextTree;
        } catch (reason) {
            setMessage(errorMessage(reason));
            return undefined;
        }
    };

    const activateFile = (key: string) => {
        const model = models.current.get(key);
        if (!model) {
            return;
        }
        activePathRef.current = key;
        setActivePath(key);
        setSelectedPath(key);
        editor.current?.setModel(model);
        editor.current?.updateOptions({readOnly: isDatapackKey(key)});
        editor.current?.focus();
        setRevision(value => value + 1);
    };

    const openFile = async (path: string, editable = true, source: "workspace" | "datapack" = "workspace") => {
        if (!editable) {
            setMessage("This file type is visible in the explorer but is not editable yet.");
            return;
        }
        const key = source === "datapack" ? `${datapackPrefix}${path}` : path;
        if (models.current.has(key)) {
            activateFile(key);
            return;
        }

        try {
            setMessage(`Opening ${path}…`);
            const endpoint = source === "datapack" ? "/datapack-file" : "/file";
            const content = await (await request(`${endpoint}?path=${encodeURIComponent(path)}`)).text();
            const model = monaco.editor.createModel(content, languageFor(path), modelUri(key));
            models.current.set(key, model);
            savedContent.current.set(key, content);
            setOpenPaths(current => current.includes(key) ? current : [...current, key]);
            activateFile(key);
            setMessage(`Opened ${path}${source === "datapack" ? " (read only)" : ""}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    const revealEntry = (entry: WorkspaceEntry) => {
        const key = entryKey(entry);
        setSidebar("explorer");
        explorer.reveal(entry);
        setSelectedPath(key);
    };

    const openSearchResult = (entry: WorkspaceEntry) => {
        revealEntry(entry);
        void openFile(entry.path, entry.editable !== false, entrySource(entry));
    };

    const showEntryMenu = (event: MouseEvent, entry: WorkspaceEntry) => {
        const supportsOverride = entrySource(entry) === "datapack" && entry.type === "file";
        const supportsCreate = entrySource(entry) === "workspace" && entry.type === "directory";
        if (!supportsOverride && !supportsCreate) return;
        event.preventDefault();
        setSelectedPath(entryKey(entry));
        setContextMenu({
            entry,
            x: Math.max(8, Math.min(event.clientX, window.innerWidth - 230)),
            y: Math.max(8, Math.min(event.clientY, window.innerHeight - 86))
        });
    };

    const createDatapackOverride = async (entry: WorkspaceEntry) => {
        const target = datapackOverridePath(entry.path);
        if (!target) {
            setMessage("This data-pack file does not have a valid override path.");
            return;
        }
        const existing = findEntry(tree?.entries, target);
        if ((existing || models.current.has(target)) && !window.confirm(
            `Replace ${target} in the workspace with ${entry.path}?${isDirty(target) ? " Unsaved changes will be discarded." : ""}`
        )) return;

        try {
            setMessage(`Creating workspace override for ${entry.path}…`);
            const response = await request(`/datapack-override?path=${encodeURIComponent(entry.path)}`, {method: "POST"});
            const result = await response.json() as { path: string };
            const nextTree = await refreshTree();
            const workspaceEntry = findEntry(nextTree?.entries, result.path) ?? {
                name: fileName(result.path),
                path: result.path,
                type: "file" as const,
                editable: true
            };

            const existingModel = models.current.get(result.path);
            if (existingModel) {
                const content = await (await request(`/file?path=${encodeURIComponent(result.path)}`)).text();
                existingModel.setValue(content);
                savedContent.current.set(result.path, content);
                activateFile(result.path);
            } else {
                await openFile(result.path, true, "workspace");
            }
            revealEntry(workspaceEntry);
            setMessage(`Created workspace override at ${result.path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    const isDirty = (path: string) => {
        const model = models.current.get(path);
        return Boolean(model && model.getValue() !== savedContent.current.get(path));
    };

    const saveFile = async (path: string) => {
        if (isDatapackKey(path)) {
            setMessage("Built-in data-pack files are read only. Copy one into the workspace to edit it.");
            return;
        }
        const model = models.current.get(path);
        if (!model) {
            return;
        }
        try {
            setMessage(`Saving ${path}…`);
            await request(`/file?path=${encodeURIComponent(path)}`, {
                method: "PUT",
                headers: {"Content-Type": "text/plain; charset=utf-8"},
                body: model.getValue()
            });
            savedContent.current.set(path, model.getValue());
            setRevision(value => value + 1);
            setMessage(`Saved ${path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    saveActiveRef.current = () => {
        if (activePathRef.current) {
            void saveFile(activePathRef.current);
        }
    };

    const closeFile = (path: string, force = false) => {
        if (!force && isDirty(path) && !window.confirm(`Discard unsaved changes in ${displayPath(path)}?`)) {
            return false;
        }
        const remaining = openPaths.filter(openPath => openPath !== path);
        models.current.get(path)?.dispose();
        models.current.delete(path);
        savedContent.current.delete(path);
        setOpenPaths(remaining);

        if (activePathRef.current === path) {
            const nextPath = remaining.at(-1);
            if (nextPath) {
                activateFile(nextPath);
            } else {
                activePathRef.current = undefined;
                setActivePath(undefined);
                editor.current?.setModel(null);
            }
        }
        return true;
    };

    const closePathAndChildren = (path: string) => {
        const affected = openPaths.filter(openPath => !isDatapackKey(openPath)
            && (openPath === path || openPath.startsWith(`${path}/`)));
        if (affected.some(isDirty) && !window.confirm("Discard unsaved changes in the affected files?")) {
            return false;
        }
        const affectedSet = new Set(affected);
        for (const openPath of affected) {
            models.current.get(openPath)?.dispose();
            models.current.delete(openPath);
            savedContent.current.delete(openPath);
        }
        const remaining = openPaths.filter(openPath => !affectedSet.has(openPath));
        setOpenPaths(remaining);
        if (activePathRef.current && affectedSet.has(activePathRef.current)) {
            const nextPath = remaining.at(-1);
            if (nextPath) {
                activateFile(nextPath);
            } else {
                activePathRef.current = undefined;
                setActivePath(undefined);
                editor.current?.setModel(null);
            }
        }
        return true;
    };

    const createEntry = async (type: "file" | "folder", requestedParent?: string) => {
        const workspaceSelection = selectedPath && !isDatapackKey(selectedPath) ? selectedPath : undefined;
        const parent = requestedParent ?? selectedDirectory(tree?.entries, workspaceSelection);
        let path: string | undefined;
        if (requestedParent !== undefined) {
            const name = window.prompt(`New ${type} name`)?.trim();
            if (!name) return;
            if (name.includes("/") || name.includes("\\")) {
                setMessage(`Enter a ${type} name without folder separators.`);
                return;
            }
            path = parent ? `${parent}/${name}` : name;
        } else {
            const suggestion = parent ? `${parent}/` : "";
            path = window.prompt(`New ${type} path`, suggestion)?.trim();
        }
        if (!path) {
            return;
        }
        try {
            await request(`/${type}?path=${encodeURIComponent(path)}`, {method: "POST"});
            explorer.expand(parent);
            await refreshTree();
            setSelectedPath(path);
            if (type === "file") {
                await openFile(path, true, "workspace");
            }
            setMessage(`Created ${path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    const renameEntry = async (entry: WorkspaceEntry) => {
        const target = window.prompt("Rename or move to", entry.path)?.trim();
        if (!target || target === entry.path || !closePathAndChildren(entry.path)) {
            return;
        }
        try {
            await request(`/move?path=${encodeURIComponent(entry.path)}&target=${encodeURIComponent(target)}`, {
                method: "POST"
            });
            await refreshTree();
            setSelectedPath(target);
            if (entry.type === "file" && entry.editable) {
                await openFile(target, true, "workspace");
            }
            setMessage(`Moved ${entry.path} to ${target}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    const deleteEntry = async (entry: WorkspaceEntry) => {
        if (!window.confirm(`Delete ${entry.path}${entry.type === "directory" ? " and everything inside it" : ""}?`)) {
            return;
        }
        if (!closePathAndChildren(entry.path)) {
            return;
        }
        try {
            await request(`/entry?path=${encodeURIComponent(entry.path)}`, {method: "DELETE"});
            await refreshTree();
            setSelectedPath(undefined);
            setMessage(`Deleted ${entry.path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    useEffect(() => {
        if (!editorHost.current) {
            return;
        }
        editor.current = monaco.editor.create(editorHost.current, {
            model: null,
            theme: "vs-dark",
            automaticLayout: true,
            fontFamily: "'JetBrains Mono', 'Cascadia Code', Consolas, monospace",
            fontSize: 14,
            lineHeight: 22,
            minimap: {enabled: false},
            padding: {top: 14, bottom: 14},
            scrollBeyondLastLine: false,
            smoothScrolling: true,
            tabSize: 2
        });
        editor.current.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveActiveRef.current());
        const modelListener = editor.current.onDidChangeModel(() => editor.current?.updateOptions({
            extraEditorClassName: editor.current.getModel()?.getLanguageId() === "json" ? "epherolib-json-editor" : ""
        }));
        const contentListener = editor.current.onDidChangeModelContent(() => setRevision(value => value + 1));
        const cursorListener = editor.current.onDidChangeCursorPosition(event => setCursor({
            line: event.position.lineNumber,
            column: event.position.column
        }));

        return () => {
            modelListener.dispose();
            contentListener.dispose();
            cursorListener.dispose();
            editor.current?.dispose();
            for (const model of models.current.values()) {
                model.dispose();
            }
            models.current.clear();
            editor.current = undefined;
        };
    }, []);

    useEffect(() => {
        void refreshTree().then(nextTree => {
            const first = findFirstEditable(nextTree?.entries ?? []);
            if (first) {
                void openFile(first.path, first.editable, "workspace");
            }
        });
    }, [token]);

    const activeReadOnly = Boolean(activePath && isDatapackKey(activePath));
    const activeDirty = activePath && !activeReadOnly ? isDirty(activePath) : false;
    void revision;

    return (
        <section class="workspace-editor flex min-h-0 min-w-0 flex-1 overflow-hidden bg-[#181818]">
            <Sidebar activeView={sidebar} onActiveViewChange={setSidebar} views={[
                {
                    id: "explorer",
                    label: "Explorer",
                    icon: <ExplorerGlyph/>,
                    content: <ExplorerView tree={tree} datapacks={datapacks} expanded={explorer.expanded}
                                           selectedPath={selectedPath} revealedPath={explorer.revealedPath}
                                           workspaceSectionOpen={explorer.workspaceSectionOpen}
                                           datapackSectionOpen={explorer.datapackSectionOpen}
                                           onWorkspaceSectionToggle={explorer.toggleWorkspaceSection}
                                           onDatapackSectionToggle={explorer.toggleDatapackSection}
                                           onToggle={explorer.toggleEntry}
                                           onSelect={setSelectedPath}
                                           onOpen={entry => void openFile(entry.path, entry.editable !== false, entrySource(entry))}
                                           onContextMenu={showEntryMenu}
                                           onRename={entry => void renameEntry(entry)}
                                           onDelete={entry => void deleteEntry(entry)}
                                           onCreate={type => void createEntry(type)}
                                           onRefresh={() => void refreshTree()}/>
                },
                {
                    id: "search",
                    label: "Search JSON files",
                    icon: <SearchGlyph/>,
                    content: <SearchView tree={tree} datapacks={datapacks} active={sidebar === "search"}
                                         selectedPath={selectedPath} onOpen={openSearchResult}
                                         onContextMenu={showEntryMenu}/>
                }
            ]}/>

            <div class="flex min-h-0 min-w-0 flex-1 flex-col bg-[#1e1e1e]">
                <div class="flex h-9 min-w-0 shrink-0 overflow-x-auto border-b border-black/50 bg-[#181818]">
                    {openPaths.map(path => (
                        <button
                            key={path}
                            type="button"
                            onClick={() => activateFile(path)}
                            class={`group flex min-w-[130px] max-w-[220px] items-center gap-2 border-r border-black/50 px-3 text-xs ${
                                activePath === path ? "border-t border-t-violet-400 bg-[#1e1e1e] text-white" : "text-zinc-400 hover:bg-[#202020]"
                            }`}
                        >
                            <FileGlyph path={displayPath(path)}/>
                            <span class="min-w-0 flex-1 truncate text-left">{fileName(displayPath(path))}</span>
                            {isDatapackKey(path) && <span class="text-[9px] text-zinc-500" title="Read only">R</span>}
                            <span
                                role="button"
                                tabIndex={0}
                                title={`Close ${displayPath(path)}`}
                                onClick={event => {
                                    event.stopPropagation();
                                    closeFile(path);
                                }}
                                onKeyDown={event => {
                                    if (event.key === "Enter" || event.key === " ") {
                                        event.stopPropagation();
                                        closeFile(path);
                                    }
                                }}
                                class="flex h-5 w-5 items-center justify-center rounded hover:bg-white/10"
                            >
                                {isDirty(path) ? "●" : "×"}
                            </span>
                        </button>
                    ))}
                </div>

                <div class="flex h-8 shrink-0 items-center justify-between border-b border-white/5 px-3 text-xs text-zinc-500">
                    <div class="flex min-w-0 items-center gap-2">
                        <span class="truncate">{activePath ? displayPath(activePath) : "No file open"}</span>
                        {activeReadOnly && <span
                            class="shrink-0 rounded bg-white/5 px-1.5 py-0.5 text-[9px] font-semibold uppercase text-zinc-400">Read only</span>}
                    </div>
                    <button
                        type="button"
                        disabled={activeReadOnly || !activeDirty}
                        onClick={() => activePath && void saveFile(activePath)}
                        class="rounded px-2 py-1 text-zinc-300 hover:bg-white/10 disabled:cursor-default disabled:opacity-30"
                    >
                        Save
                    </button>
                </div>

                <div class="relative min-h-0 flex-1">
                    {!activePath && (
                        <div
                            class="pointer-events-none absolute inset-0 z-10 flex items-center justify-center text-sm text-zinc-600">
                            Select a file from Explorer
                        </div>
                    )}
                    <div ref={editorHost} class="absolute inset-0"/>
                </div>

                <footer class="flex h-6 shrink-0 items-center justify-between gap-2 overflow-hidden whitespace-nowrap bg-violet-700 px-2 text-[11px] text-violet-50">
                    <span class="max-w-[60%] truncate">{message}</span>
                    <div class="flex items-center gap-4">
                        <span>Ln {cursor.line}, Col {cursor.column}</span>
                        <span>{activePath ? languageLabel(displayPath(activePath)) : "Plain Text"}</span>
                        <span>UTF-8</span>
                    </div>
                </footer>
            </div>

            {contextMenu && (
                <div class="fixed inset-0 z-50" onClick={() => setContextMenu(undefined)}
                     onContextMenu={event => event.preventDefault()}>
                    <div role="menu" aria-label={`Actions for ${contextMenu.entry.name}`}
                         class="fixed min-w-[220px] border border-white/15 bg-[#252526] py-1 shadow-2xl shadow-black/60"
                         style={{left: `${contextMenu.x}px`, top: `${contextMenu.y}px`}}
                         onClick={event => event.stopPropagation()}>
                        {entrySource(contextMenu.entry) === "workspace" && contextMenu.entry.type === "directory" ? (
                            <button type="button" role="menuitem"
                                    class="flex w-full px-3 py-2 text-left text-xs text-zinc-200 hover:bg-violet-600 hover:text-white"
                                    onClick={() => {
                                        const folder = contextMenu.entry.path;
                                        setContextMenu(undefined);
                                        void createEntry("file", folder);
                                    }}>
                                New File…
                            </button>
                        ) : (
                            <button type="button" role="menuitem"
                                    class="flex w-full flex-col px-3 py-2 text-left text-xs text-zinc-200 hover:bg-violet-600 hover:text-white"
                                    onClick={() => {
                                        const entry = contextMenu.entry;
                                        setContextMenu(undefined);
                                        void createDatapackOverride(entry);
                                    }}>
                                <span>Override in Workspace</span>
                                <span class="mt-0.5 max-w-[320px] truncate text-[10px] opacity-70"
                                      title={datapackOverridePath(contextMenu.entry.path) ?? undefined}>
                                    {datapackOverridePath(contextMenu.entry.path)}
                                </span>
                            </button>
                        )}
                    </div>
                </div>
            )}
        </section>
    );
}

function modelUri(key: string) {
    const root = isDatapackKey(key) ? "loaded-datapacks" : "epherolib-workspace";
    return monaco.Uri.from({scheme: "file", path: `/${root}/${displayPath(key)}`});
}

function languageFor(path: string) {
    if (path.endsWith(".js")) return "javascript";
    if (path.endsWith(".json")) return "json";
    return "plaintext";
}

function languageLabel(path: string) {
    if (path.endsWith(".js")) return "JavaScript";
    if (path.endsWith(".json")) return "JSON";
    return "Plain Text";
}

function errorMessage(reason: unknown) {
    return reason instanceof Error ? reason.message : String(reason);
}
