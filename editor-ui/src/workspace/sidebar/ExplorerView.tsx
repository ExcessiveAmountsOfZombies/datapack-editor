import type {ComponentChildren} from "preact";
import {useEffect, useRef, useState} from "preact/hooks";
import {ChevronIcon, DeleteIcon, FileGlyph, FolderIcon, RefreshIcon, RenameIcon} from "../Codicon";
import {
    ancestorKeys,
    entryKey,
    entrySource,
    toggleSet,
    type WorkspaceEntry,
    workspaceMoveTarget,
    type WorkspaceTree
} from "../model";

export function useExplorerState(active: boolean) {
    const [expanded, setExpanded] = useState(() => new Set<string>());
    const [workspaceSectionOpen, setWorkspaceSectionOpen] = useState(true);
    const [datapackSectionOpen, setDatapackSectionOpen] = useState(true);
    const [revealedPath, setRevealedPath] = useState<string>();
    const revealTimer = useRef<number>();

    useEffect(() => {
        if (!revealedPath || !active) return;
        const frame = window.requestAnimationFrame(() => {
            const row = Array.from(document.querySelectorAll<HTMLElement>("[data-entry-key]"))
                .find(element => element.dataset.entryKey === revealedPath);
            row?.scrollIntoView({block: "center"});
        });
        return () => window.cancelAnimationFrame(frame);
    }, [revealedPath, active, expanded]);

    useEffect(() => () => {
        if (revealTimer.current !== undefined) window.clearTimeout(revealTimer.current);
    }, []);

    return {
        expanded,
        workspaceSectionOpen,
        datapackSectionOpen,
        revealedPath,
        toggleWorkspaceSection: () => setWorkspaceSectionOpen(value => !value),
        toggleDatapackSection: () => setDatapackSectionOpen(value => !value),
        toggleEntry: (key: string) => setExpanded(current => toggleSet(current, key)),
        initialize: (entries: WorkspaceEntry[]) => setExpanded(current => current.size > 0 ? current : new Set(entries
            .filter(entry => entry.type === "directory")
            .map(entry => entryKey(entry)))),
        expand: (key: string) => setExpanded(current => new Set(current).add(key)),
        remap: (source: string, target: string) => setExpanded(current => new Set([...current].map(key =>
            key === source ? target : key.startsWith(`${source}/`) ? `${target}${key.slice(source.length)}` : key))),
        reveal: (entry: WorkspaceEntry) => {
            const key = entryKey(entry);
            if (entrySource(entry) === "datapack") setDatapackSectionOpen(true);
            else setWorkspaceSectionOpen(true);
            setExpanded(current => {
                const next = new Set(current);
                for (const ancestor of ancestorKeys(entry)) next.add(ancestor);
                return next;
            });
            setRevealedPath(key);
            if (revealTimer.current !== undefined) window.clearTimeout(revealTimer.current);
            revealTimer.current = window.setTimeout(() => {
                setRevealedPath(current => current === key ? undefined : current);
                revealTimer.current = undefined;
            }, 2200);
        }
    };
}

interface ExplorerViewProps {
    tree?: WorkspaceTree;
    datapacks?: WorkspaceTree;
    expanded: Set<string>;
    selectedPath?: string;
    revealedPath?: string;
    workspaceSectionOpen: boolean;
    datapackSectionOpen: boolean;
    onWorkspaceSectionToggle: () => void;
    onDatapackSectionToggle: () => void;
    onToggle: (key: string) => void;
    onSelect: (key: string) => void;
    onOpen: (entry: WorkspaceEntry) => void;
    onContextMenu: (event: MouseEvent, entry: WorkspaceEntry) => void;
    onRename: (entry: WorkspaceEntry) => void;
    onDelete: (entry: WorkspaceEntry) => void;
    onCreate: (type: "file" | "folder") => void;
    onRefresh: () => void;
    onMove: (entry: WorkspaceEntry, target: string) => void;
}

export function ExplorerView(props: ExplorerViewProps) {
    const [dragged, setDragged] = useState<WorkspaceEntry>();
    const [dropTarget, setDropTarget] = useState<string>();
    const targetFor = (entry: WorkspaceEntry, directory: string) =>
        workspaceMoveTarget(props.tree?.entries, entry, directory);
    const clearDrag = () => {
        setDragged(undefined);
        setDropTarget(undefined);
    };
    const dropInto = (event: DragEvent, directory: string) => {
        event.preventDefault();
        event.stopPropagation();
        const source = event.dataTransfer?.getData("application/x-epherolib-workspace");
        const target = dragged && source === dragged.path && targetFor(dragged, directory);
        if (dragged && target) props.onMove(dragged, target);
        clearDrag();
    };
    const rows = (entries: WorkspaceEntry[] | undefined) => entries?.map(entry => (
        <TreeRow key={entryKey(entry)} entry={entry} depth={0} expanded={props.expanded}
                 selectedPath={props.selectedPath} revealedPath={props.revealedPath}
                 onToggle={props.onToggle} onSelect={props.onSelect} onOpen={props.onOpen}
                 onContextMenu={props.onContextMenu} onRename={props.onRename} onDelete={props.onDelete}
                 dragged={dragged} dropTarget={dropTarget} onDragStart={setDragged}
                 onDragEnd={clearDrag} onDropTarget={setDropTarget} onDropInto={dropInto}
                 targetFor={targetFor}/>
    ));

    return (
        <>
            <div
                class="flex h-11 items-center justify-between px-4 text-[11px] font-medium uppercase tracking-wide text-zinc-300">
                <span>Explorer</span>
                <div class="flex items-center gap-0.5">
                    <ToolButton label="Refresh" onClick={props.onRefresh}><RefreshIcon/></ToolButton>
                </div>
            </div>
            <div class="min-h-0 flex-1 overflow-auto text-[13px]">
                <ExplorerSection title={props.tree?.root ?? "Workspace"} open={props.workspaceSectionOpen}
                                 onContextMenu={event => props.onContextMenu(event, {
                                     name: props.tree?.root ?? "Workspace", path: "", type: "directory", source: "workspace"
                                 })}
                                 onToggle={props.onWorkspaceSectionToggle} dropActive={dropTarget === ""}
                                 onDragOver={event => {
                                     if (!dragged || !targetFor(dragged, "")) return;
                                     event.preventDefault();
                                     if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
                                     setDropTarget("");
                                 }} onDragLeave={event => {
                    if (event.relatedTarget instanceof Node && event.currentTarget instanceof Node
                        && event.currentTarget.contains(event.relatedTarget)) return;
                    setDropTarget(current => current === "" ? undefined : current);
                }}
                                 onDrop={event => dropInto(event, "")}>
                    {rows(props.tree?.entries)}
                </ExplorerSection>
                <ExplorerSection title={props.datapacks?.root ?? "Loaded Datapacks"}
                                 detail={props.datapacks ? String(props.datapacks.fileCount ?? 0) : undefined}
                                 open={props.datapackSectionOpen} onToggle={props.onDatapackSectionToggle}>
                    {rows(props.datapacks?.entries)}
                </ExplorerSection>
            </div>
        </>
    );
}

interface TreeRowProps {
    entry: WorkspaceEntry;
    depth: number;
    expanded: Set<string>;
    selectedPath?: string;
    revealedPath?: string;
    onToggle: (key: string) => void;
    onSelect: (key: string) => void;
    onOpen: (entry: WorkspaceEntry) => void;
    onContextMenu: (event: MouseEvent, entry: WorkspaceEntry) => void;
    onRename: (entry: WorkspaceEntry) => void;
    onDelete: (entry: WorkspaceEntry) => void;
    dragged?: WorkspaceEntry;
    dropTarget?: string;
    onDragStart: (entry: WorkspaceEntry) => void;
    onDragEnd: () => void;
    onDropTarget: (path: string | undefined) => void;
    onDropInto: (event: DragEvent, directory: string) => void;
    targetFor: (entry: WorkspaceEntry, directory: string) => string | undefined;
}

function TreeRow(props: TreeRowProps) {
    const {entry} = props;
    const isDirectory = entry.type === "directory";
    const key = entryKey(entry);
    const isExpanded = props.expanded.has(key);
    const workspaceEntry = entrySource(entry) === "workspace" && !entry.readOnly;
    const canDrop = workspaceEntry && isDirectory && props.dragged &&
        props.targetFor(props.dragged, entry.path) !== undefined;
    return (
        <>
            <div data-entry-key={key} title={entry.path} draggable={workspaceEntry}
                 class={`group flex h-6 cursor-default items-center pr-1 ${
                     props.dropTarget === entry.path && canDrop
                         ? "bg-violet-600/40 text-white outline outline-1 -outline-offset-1 outline-violet-400"
                         : props.revealedPath === key
                             ? "bg-violet-500/35 text-white outline outline-1 -outline-offset-1 outline-violet-300/70"
                             : props.selectedPath === key ? "bg-[#37373d] text-white" : "text-zinc-300 hover:bg-[#2a2d2e]"
                 }`}
                 style={{paddingLeft: `${6 + props.depth * 12}px`}}
                 onDragStart={event => {
                     if (!workspaceEntry || !event.dataTransfer) {
                         event.preventDefault();
                         return;
                     }
                     event.dataTransfer.effectAllowed = "move";
                     event.dataTransfer.setData("application/x-epherolib-workspace", entry.path);
                     props.onDragStart(entry);
                 }} onDragEnd={props.onDragEnd}
                 onDragOver={event => {
                     if (!props.dragged) return;
                     event.stopPropagation();
                     if (!canDrop) {
                         props.onDropTarget(undefined);
                         return;
                     }
                     event.preventDefault();
                     if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
                     props.onDropTarget(entry.path);
                 }} onDragLeave={event => {
                if (event.relatedTarget instanceof Node && event.currentTarget.contains(event.relatedTarget)) return;
                if (props.dropTarget === entry.path) props.onDropTarget(undefined);
            }} onDrop={event => {
                event.stopPropagation();
                if (canDrop) props.onDropInto(event, entry.path);
            }}
                 onClick={() => {
                     props.onSelect(key);
                     if (isDirectory) props.onToggle(key);
                     else props.onOpen(entry);
                 }}
                 onContextMenu={event => {
                     props.onSelect(key);
                     props.onContextMenu(event, entry);
                 }}>
                <span class="mr-1 inline-flex w-3 shrink-0 justify-center text-zinc-500">
                    {isDirectory && <ChevronIcon open={isExpanded}/>}
                </span>
                {isDirectory ? <FolderIcon open={isExpanded}/> : <FileGlyph path={entry.path}/>}
                <span class={`ml-1.5 min-w-0 flex-1 truncate ${entry.editable === false ? "opacity-50" : ""}`}>
                    {entry.name}
                </span>
                {!entry.readOnly && (
                    <span class="hidden items-center gap-0.5 group-hover:flex">
                        <RowAction label="Rename" onClick={() => props.onRename(entry)}><RenameIcon/></RowAction>
                        <RowAction label="Delete" onClick={() => props.onDelete(entry)}><DeleteIcon/></RowAction>
                    </span>
                )}
            </div>
            {isDirectory && isExpanded && entry.children?.map(child => (
                <TreeRow key={entryKey(child)} {...props} entry={child} depth={props.depth + 1}/>
            ))}
        </>
    );
}

function ExplorerSection(props: {
    title: string; detail?: string; open: boolean; onToggle: () => void;
    children?: ComponentChildren; dropActive?: boolean; onDragOver?: (event: DragEvent) => void;
    onDragLeave?: (event: DragEvent) => void; onDrop?: (event: DragEvent) => void;
    onContextMenu?: (event: MouseEvent) => void
}) {
    return (
        <section>
            <button type="button"
                    class={`sticky top-0 z-10 flex h-7 w-full items-center border-y px-2 text-left text-[11px] font-bold uppercase tracking-wide hover:bg-[#252525] ${props.dropActive
                        ? "border-violet-400 bg-violet-600/40 text-white" : "border-white/5 bg-[#1f1f1f] text-zinc-200"}`}
                    onDragOver={props.onDragOver} onDragLeave={props.onDragLeave} onDrop={props.onDrop}
                    onClick={props.onToggle} onContextMenu={props.onContextMenu} aria-expanded={props.open}>
                <ChevronIcon open={props.open} class="mr-1"/>
                <FolderIcon open={props.open}/>
                <span class="ml-1.5 min-w-0 flex-1 truncate">{props.title}</span>
                {props.detail && <span class="ml-2 font-normal text-zinc-500">{props.detail}</span>}
            </button>
            {props.open && <div class="py-1">{props.children}</div>}
        </section>
    );
}

function ToolButton(props: { label: string; onClick: () => void; children: ComponentChildren }) {
    return (
        <button type="button" title={props.label} aria-label={props.label} onClick={props.onClick}
                class="flex h-6 items-center justify-center rounded px-1 text-zinc-400 hover:bg-white/10 hover:text-white">
            {props.children}
        </button>
    );
}

function RowAction(props: { label: string; onClick: () => void; children: ComponentChildren }) {
    return (
        <button type="button" title={props.label} aria-label={props.label}
                onClick={event => {
                    event.stopPropagation();
                    props.onClick();
                }}
                class="flex h-5 w-5 items-center justify-center rounded text-[10px] text-zinc-400 hover:bg-white/10 hover:text-white">
            {props.children}
        </button>
    );
}
