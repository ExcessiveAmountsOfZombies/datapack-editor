import {useCallback, useEffect, useMemo, useRef, useState} from "preact/hooks";
import {ExplorerView, useExplorerState} from "./sidebar/ExplorerView";
import {CloseIcon, ExplorerIcon, FileGlyph, NotificationIcon, ReadOnlyIcon, SearchIcon, TabCloseIcon} from "./Codicon";
import {ExportDatapackDialog, type DatapackExportSettings} from "./ExportDatapackDialog";
import {createNotificationService, type WorkspaceNotification} from "./notifications";
import {LootPreview} from "./preview/LootPreview";
import {AdvancementPreview} from "./preview/AdvancementPreview";
import {PreviewPanel} from "./preview/PreviewPanel";
import {RecipePreview} from "./preview/RecipePreview";
import {ConfiguredFeaturePreview} from "./preview/ConfiguredFeaturePreview";
import {featureResource, isConfiguredFeaturePath, type FeatureSettings, type FeatureSimulation} from "./preview/configuredFeature";
import {type ChestSimulation, isChestLootTablePath, lootTableId} from "./preview/lootTable";
import {isRecipePath} from "./preview/recipeDocument";
import {preloadItemAssets} from "./preview/modelRenderer";
import {isAdvancementPath} from "./preview/advancement";
import {SearchView} from "./sidebar/SearchView";
import {Sidebar} from "./sidebar/Sidebar";
import {
    countEntries,
    datapackOverridePath,
    datapackPrefix,
    displayPath,
    entryKey,
    entrySource,
    fileName,
    findEntry,
    findFirstEditable,
    isDatapackKey,
    selectedDirectory,
    type WorkspaceEntry,
    type WorkspaceTree
} from "./model";
import {
    configureDataPackSchemas,
    configureRecipeSchema,
    configureTagSchemas,
    registerWorkspaceCompletions,
    type JsonSchemaRegistration,
    monaco
} from "./monaco";

interface ExplorerContextMenu {
    entry: WorkspaceEntry;
    x: number;
    y: number;
}

const apiRoot = "/api";

export function WorkspaceEditor() {
    const editorHost = useRef<HTMLDivElement>(null);
    const editor = useRef<monaco.editor.IStandaloneCodeEditor>();
    const models = useRef(new Map<string, monaco.editor.ITextModel>());
    const savedContent = useRef(new Map<string, string>());
    const moveInProgress = useRef(false);
    const activePathRef = useRef<string>();
    const saveActiveRef = useRef<() => void>(() => undefined);
    const [tree, setTree] = useState<WorkspaceTree>();
    const [datapacks, setDatapacks] = useState<WorkspaceTree>();
    const [sidebar, setSidebar] = useState<string | null>("explorer");
    const explorer = useExplorerState(sidebar === "explorer");
    const [contextMenu, setContextMenu] = useState<ExplorerContextMenu>();
    const [exportDialogOpen, setExportDialogOpen] = useState(false);
    const [notification, setNotification] = useState<WorkspaceNotification>();
    const notifications = useRef(createNotificationService(setNotification));
    const [selectedPath, setSelectedPath] = useState<string>();
    const [openPaths, setOpenPaths] = useState<string[]>([]);
    const [activePath, setActivePath] = useState<string>();
    const [revision, setRevision] = useState(0);
    const [simulationRevision, setSimulationRevision] = useState(0);
    const [message, setMessage] = useState("Loading workspace…");
    const [cursor, setCursor] = useState({line: 1, column: 1});

    const request = async (path: string, init?: RequestInit) => {
        const response = await fetch(`${apiRoot}${path}`, init);
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

    const simulateLoot = useCallback(async (content: string): Promise<ChestSimulation> => {
        const id = lootTableId(activePath);
        const response = await fetch(`${apiRoot}/chest-simulation${id ? `?id=${encodeURIComponent(id)}` : ""}`, {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: content
        });
        if (!response.ok) {
            const result = await response.json() as { error?: string };
            throw new Error(result.error ?? `Loot simulation failed (${response.status})`);
        }
        return response.json() as Promise<ChestSimulation>;
    }, [activePath]);

    const simulateFeature = useCallback(async (content: string, settings: FeatureSettings, signal: AbortSignal): Promise<FeatureSimulation> => {
        const overrides: Record<string, unknown> = {};
        for (const [path, model] of models.current) {
            if (isDatapackKey(path)) continue;
            const resource = featureResource(displayPath(path));
            if (!resource) continue;
            try { overrides[`${resource.kind}/${resource.id}`] = JSON.parse(model.getValue()); }
            catch { overrides[`${resource.kind}/${resource.id}`] = model.getValue(); }
        }
        const response = await fetch(`${apiRoot}/configured-feature-preview`, {
            method: "POST", headers: {"Content-Type": "application/json"}, signal,
            body: JSON.stringify({feature: JSON.parse(content), ...settings, overrides})
        });
        if (!response.ok) {
            const result = await response.json() as {error?: string};
            throw new Error(result.error ?? `Feature generation failed (${response.status})`);
        }
        return response.json() as Promise<FeatureSimulation>;
    }, []);

    useEffect(() => {
        preloadItemAssets();
    }, []);

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
                const result = response as { schemas?: JsonSchemaRegistration[] };
                if (!cancelled) configureTagSchemas(result.schemas ?? []);
            })
            .catch(reason => {
                if (!cancelled) setMessage(`Tag completions unavailable: ${errorMessage(reason)}`);
            });
        request("/schema/datapack")
            .then(response => response.json())
            .then(response => {
                const result = response as { schemas?: JsonSchemaRegistration[] };
                if (!cancelled) configureDataPackSchemas(result.schemas ?? []);
            })
            .catch(reason => {
                if (!cancelled) setMessage(`Datapack completions unavailable: ${errorMessage(reason)}`);
            });
        return () => {
            cancelled = true;
        };
    }, []);

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
            return false;
        }
        const key = source === "datapack" ? `${datapackPrefix}${path}` : path;
        if (models.current.has(key)) {
            activateFile(key);
            return true;
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
            return true;
        } catch (reason) {
            setMessage(errorMessage(reason));
            return false;
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
            y: Math.max(8, Math.min(event.clientY, window.innerHeight - (entry.path === "" ? 125 : 86)))
        });
    };

    const dirtyWorkspacePaths = () => [...models.current.keys()].filter(path => !isDatapackKey(path) && isDirty(path));

    const exportDatapack = async (settings: DatapackExportSettings, saveAll: boolean) => {
        const dirtyPaths = dirtyWorkspacePaths();
        if (dirtyPaths.length > 0 && !saveAll) throw new Error("Save unsaved workspace changes before exporting.");
        for (const path of dirtyPaths) {
            try {
                await persistFile(path);
            } catch (reason) {
                throw new Error(`Could not save ${path}: ${errorMessage(reason)}`);
            }
        }
        if (dirtyWorkspacePaths().length > 0) throw new Error("Workspace changes are still unsaved. Save All and Export again.");
        const response = await request("/export-datapack", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify(settings)
        });
        const result = await response.json() as {path: string};
        setExportDialogOpen(false);
        const message = `Datapack exported to ${result.path}`;
        setMessage(message);
        notifications.current.info(message);
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

    const persistFile = async (path: string) => {
        if (isDatapackKey(path)) {
            throw new Error("Built-in data-pack files are read only. Copy one into the workspace to edit it.");
        }
        const model = models.current.get(path);
        if (!model) {
            return;
        }
        const content = model.getValue();
        setMessage(`Saving ${path}…`);
        await request(`/file?path=${encodeURIComponent(path)}`, {
            method: "PUT",
            headers: {"Content-Type": "text/plain; charset=utf-8"},
            body: content
        });
        savedContent.current.set(path, content);
        setRevision(value => value + 1);
        setSimulationRevision(value => value + 1);
        setMessage(`Saved ${path}`);
    };

    const saveFile = async (path: string) => {
        try {
            await persistFile(path);
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
            const nextTree = await refreshTree();
            const created = findEntry(nextTree?.entries, path);
            if (created) revealEntry(created);
            else setSelectedPath(path);
            if (type === "file") {
                await openFile(path, true, "workspace");
            }
            setMessage(`Created ${path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
        }
    };

    const createAdvancement = async (path: string, content: string) => {
        try {
            if (findEntry(tree?.entries, path)) throw new Error(`Workspace file already exists: ${path}`);
            const folders = path.split("/").slice(0, -1);
            for (let index = 1; index <= folders.length; index++) {
                const folder = folders.slice(0, index).join("/");
                if (findEntry(tree?.entries, folder)?.type === "directory") continue;
                try {
                    await request(`/folder?path=${encodeURIComponent(folder)}`, {method: "POST"});
                } catch (reason) {
                    if (!errorMessage(reason).includes("already exists")) throw reason;
                }
            }
            await request(`/file?path=${encodeURIComponent(path)}`, {method: "POST"});
            await request(`/file?path=${encodeURIComponent(path)}`, {
                method: "PUT", headers: {"Content-Type": "text/plain; charset=utf-8"}, body: content
            });
            for (let index = 1; index <= folders.length; index++) {
                explorer.expand(folders.slice(0, index).join("/"));
            }
            const nextTree = await refreshTree();
            if (!await openFile(path, true, "workspace")) {
                throw new Error(`Advancement was created, but ${path} could not be opened.`);
            }
            const entry = findEntry(nextTree?.entries, path);
            if (entry) revealEntry(entry);
            setMessage(`Created advancement ${path}`);
        } catch (reason) {
            setMessage(errorMessage(reason));
            throw reason;
        }
    };

    const moveEntry = async (entry: WorkspaceEntry, target: string) => {
        if (moveInProgress.current) return;
        if (entrySource(entry) !== "workspace" || entry.readOnly || target === entry.path) return;
        if (entry.type === "directory" && target.startsWith(`${entry.path}/`)) {
            setMessage("A folder cannot be moved inside itself.");
            return;
        }
        if (findEntry(tree?.entries, target)) {
            setMessage(`Workspace entry already exists: ${target}`);
            return;
        }
        const remap = (key: string) => key === entry.path ? target
            : key.startsWith(`${entry.path}/`) ? `${target}${key.slice(entry.path.length)}` : key;
        const affected = [...models.current.entries()].filter(([key]) => !isDatapackKey(key)
            && (key === entry.path || key.startsWith(`${entry.path}/`)));
        if (affected.some(([key]) => models.current.has(remap(key)) && !affected.some(([other]) => other === remap(key)))) {
            setMessage("Close the file already open at the destination before moving this entry.");
            return;
        }
        moveInProgress.current = true;
        const replacements: Array<{
            oldKey: string; newKey: string; oldModel: monaco.editor.ITextModel;
            newModel: monaco.editor.ITextModel; saved?: string
        }> = [];
        try {
            for (const [key, model] of affected) replacements.push({
                oldKey: key, newKey: remap(key), oldModel: model,
                newModel: monaco.editor.createModel(model.getValue(), languageFor(remap(key)), modelUri(remap(key))),
                saved: savedContent.current.get(key)
            });
            await request(`/move?path=${encodeURIComponent(entry.path)}&target=${encodeURIComponent(target)}`, {
                method: "POST"
            });
            for (const replacement of replacements) {
                models.current.delete(replacement.oldKey);
                savedContent.current.delete(replacement.oldKey);
                models.current.set(replacement.newKey, replacement.newModel);
                if (replacement.saved !== undefined) savedContent.current.set(replacement.newKey, replacement.saved);
            }
            if (activePathRef.current && remap(activePathRef.current) !== activePathRef.current) {
                const nextActive = remap(activePathRef.current);
                activePathRef.current = nextActive;
                setActivePath(nextActive);
                editor.current?.setModel(models.current.get(nextActive) ?? null);
            }
            for (const replacement of replacements) replacement.oldModel.dispose();
            setOpenPaths(current => current.map(remap));
            setRevision(value => value + 1);
            explorer.remap(entry.path, target);
            const nextTree = await refreshTree();
            setSelectedPath(target);
            const moved = findEntry(nextTree?.entries, target);
            if (moved) revealEntry(moved);
            setMessage(nextTree ? `Moved ${entry.path} to ${target}` :
                `Moved ${entry.path} to ${target}; refresh Explorer to see it.`);
        } catch (reason) {
            for (const replacement of replacements) {
                if (models.current.get(replacement.newKey) !== replacement.newModel) replacement.newModel.dispose();
            }
            setMessage(errorMessage(reason));
        } finally {
            moveInProgress.current = false;
        }
    };

    const renameEntry = async (entry: WorkspaceEntry) => {
        const target = window.prompt("Rename or move to", entry.path)?.trim();
        if (target) await moveEntry(entry, target);
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
        }, {notificationService: notifications.current});
        registerWorkspaceCompletions();
        editor.current.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveActiveRef.current());
        const modelListener = editor.current.onDidChangeModel(() => editor.current?.updateOptions({
            extraEditorClassName: editor.current.getModel()?.getLanguageId() === "json" ? "datapack-editor-json-editor" : ""
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
    }, []);

    const activeReadOnly = Boolean(activePath && isDatapackKey(activePath));
    const activeDirty = activePath && !activeReadOnly ? isDirty(activePath) : false;
    const openAdvancements = useMemo(() => [...models.current.entries()]
        .filter(([key]) => isAdvancementPath(displayPath(key)))
        .map(([key, model]) => ({path: displayPath(key), content: model.getValue()})), [revision, openPaths]);
    const activeDisplayPath = activePath ? displayPath(activePath) : undefined;
    const showingAdvancements = isAdvancementPath(activeDisplayPath);
    const replaceActiveContent = (content: string) => {
        if (!activePath || activeReadOnly) return;
        const model = models.current.get(activePath);
        if (!model) return;
        model.pushStackElement();
        model.pushEditOperations([], [{range: model.getFullModelRange(), text: content}], () => null);
        model.pushStackElement();
    };

    return (
        <section class="workspace-editor flex min-h-0 min-w-0 flex-1 overflow-hidden bg-[#181818]">
            <Sidebar activeView={sidebar} onActiveViewChange={setSidebar} views={[
                {
                    id: "explorer",
                    label: "Explorer",
                    icon: <ExplorerIcon/>,
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
                                           onMove={(entry, target) => void moveEntry(entry, target)}
                                           onDelete={entry => void deleteEntry(entry)}
                                           onCreate={type => void createEntry(type)}
                                           onRefresh={() => void refreshTree()}/>
                },
                {
                    id: "search",
                    label: "Search JSON files",
                    icon: <SearchIcon/>,
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
                            class={`group flex min-w-32.5 max-w-55 items-center gap-2 border-r border-black/50 px-3 text-xs ${
                                activePath === path ? "border-t border-t-violet-400 bg-[#1e1e1e] text-white" : "text-zinc-400 hover:bg-[#202020]"
                            }`}
                        >
                            <FileGlyph path={displayPath(path)}/>
                            <span class="min-w-0 flex-1 truncate text-left">{fileName(displayPath(path))}</span>
                            {isDatapackKey(path) && <span class="inline-flex text-zinc-500" title="Read only" aria-label="Read only"><ReadOnlyIcon/></span>}
                            <span
                                role="button"
                                tabIndex={0}
                                title={`Close ${displayPath(path)}`}
                                aria-label={`Close ${displayPath(path)}`}
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
                                <TabCloseIcon dirty={isDirty(path)}/>
                            </span>
                        </button>
                    ))}
                </div>

                <div
                    class="flex h-8 shrink-0 items-center justify-between border-b border-white/5 px-3 text-xs text-zinc-500">
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
                            Select a file
                        </div>
                    )}
                    <div ref={editorHost} class="absolute inset-0"/>
                </div>

                <footer
                    class="flex h-6 shrink-0 items-center justify-between gap-2 overflow-hidden whitespace-nowrap bg-violet-700 px-2 text-[11px] text-violet-50">
                    <span class="max-w-[60%] truncate">{message}</span>
                    <div class="flex items-center gap-4">
                        <span>Ln {cursor.line}, Col {cursor.column}</span>
                        <span>{activePath ? languageLabel(displayPath(activePath)) : "Plain Text"}</span>
                        <span>UTF-8</span>
                    </div>
                </footer>
            </div>

            <PreviewPanel>
                {isConfiguredFeaturePath(activeDisplayPath) && activePath &&
                    <ConfiguredFeaturePreview key={activePath} content={models.current.get(activePath)?.getValue() ?? ""}
                                              referenceRevision={revision + simulationRevision} simulate={simulateFeature}/>}
                {!showingAdvancements && isRecipePath(activeDisplayPath) &&
                    <RecipePreview path={activePath ? displayPath(activePath) : undefined}
                                   content={activePath ? models.current.get(activePath)?.getValue() : undefined}
                                   readOnly={activeReadOnly} onContentChange={replaceActiveContent}/>}
                {!showingAdvancements && isChestLootTablePath(activeDisplayPath) &&
                    <LootPreview content={activePath ? models.current.get(activePath)?.getValue() : undefined}
                                 simulationRevision={simulationRevision} simulateLoot={simulateLoot}/>}
                {showingAdvancements &&
                    <AdvancementPreview path={activeDisplayPath}
                                        workspace={tree} datapacks={datapacks}
                                        openContents={openAdvancements}
                                        savedRevision={simulationRevision} readOnly={activeReadOnly}
                                        content={isAdvancementPath(activeDisplayPath) && activePath
                                            ? models.current.get(activePath)?.getValue() : undefined}
                                        onContentChange={replaceActiveContent} onCreate={createAdvancement}
                                        onOpen={(path, source) => void openFile(path, true, source)}/>}
            </PreviewPanel>

            {contextMenu && (
                <div class="fixed inset-0 z-50" onClick={() => setContextMenu(undefined)}
                     onContextMenu={event => event.preventDefault()}>
                    <div role="menu" aria-label={`Actions for ${contextMenu.entry.name}`}
                         class="fixed min-w-55 border border-white/15 bg-[#252526] py-1 shadow-2xl shadow-black/60"
                         style={{left: `${contextMenu.x}px`, top: `${contextMenu.y}px`}}
                         onClick={event => event.stopPropagation()}>
                        {entrySource(contextMenu.entry) === "workspace" && contextMenu.entry.type === "directory" ? (
                            <>
                                <button type="button" role="menuitem"
                                        class="flex w-full px-3 py-2 text-left text-xs text-zinc-200 hover:bg-violet-600 hover:text-white"
                                        onClick={() => {
                                            const folder = contextMenu.entry.path;
                                            setContextMenu(undefined);
                                            void createEntry("file", folder);
                                        }}>
                                    New File…
                                </button>
                                <button type="button" role="menuitem"
                                        class="flex w-full px-3 py-2 text-left text-xs text-zinc-200 hover:bg-violet-600 hover:text-white"
                                        onClick={() => {
                                            const folder = contextMenu.entry.path;
                                            setContextMenu(undefined);
                                            void createEntry("folder", folder);
                                        }}>
                                    New Folder…
                                </button>
                                {contextMenu.entry.path === "" && <>
                                    <div class="my-1 border-t border-white/10"/>
                                    <button type="button" role="menuitem"
                                            class="flex w-full px-3 py-2 text-left text-xs text-zinc-200 hover:bg-violet-600 hover:text-white"
                                            onClick={() => {
                                                setContextMenu(undefined);
                                                setExportDialogOpen(true);
                                            }}>Export Datapack</button>
                                </>}
                            </>
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
            {exportDialogOpen && <ExportDatapackDialog unsavedPaths={dirtyWorkspacePaths()} onExport={exportDatapack}
                                                      onCancel={() => setExportDialogOpen(false)}/>}
            {notification && <div role={notification.severity === 3 ? "alert" : "status"}
                                  class="fixed bottom-9 right-4 z-50 flex w-[min(480px,calc(100vw-32px))] items-start gap-3 border border-white/15 bg-[#252526] p-4 text-xs text-zinc-100 shadow-2xl">
                <NotificationIcon error={notification.severity === 3}/>
                <p class="min-w-0 flex-1 whitespace-pre-wrap wrap-break-word select-text">{notification.message}</p>
                <button type="button" aria-label="Dismiss notification" onClick={() => setNotification(undefined)}
                        class="pt-1 pb-1 px-1 hover:bg-white/10"><CloseIcon/></button>
            </div>}
        </section>
    );
}

function modelUri(key: string) {
    const root = isDatapackKey(key) ? "loaded-datapacks" : "workspace";
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
