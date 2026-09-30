type CodiconName = "add" | "chevron-down" | "chevron-right" | "circle-filled" | "close" |
    "edit" | "eye" | "file" | "file-code" | "files" | "folder" | "folder-opened" | "json" |
    "lock" | "refresh" | "search" | "trash" | "info" | "error";

function Codicon({name, size = 16, class: className = ""}: {
    name: CodiconName;
    size?: number;
    class?: string;
}) {
    return <span class={`codicon codicon-${name} shrink-0 ${className}`}
                 style={{fontSize: `${size}px`}} aria-hidden="true"/>;
}

export function ExplorerIcon() {
    return <Codicon name="files" size={24}/>;
}

export function SearchIcon() {
    return <Codicon name="search" size={24}/>;
}

export function VisualizerIcon() {
    return <Codicon name="eye" size={20}/>;
}

export function FolderIcon({open}: {open: boolean}) {
    return <Codicon name={open ? "folder-opened" : "folder"} class="text-amber-400"/>;
}

export function ChevronIcon({open, class: className}: {open: boolean; class?: string}) {
    return <Codicon name={open ? "chevron-down" : "chevron-right"} size={12} class={className}/>;
}

export function RefreshIcon() {
    return <Codicon name="refresh"/>;
}

export function RenameIcon() {
    return <Codicon name="edit" size={14}/>;
}

export function DeleteIcon() {
    return <Codicon name="trash" size={14}/>;
}

export function ReadOnlyIcon() {
    return <Codicon name="lock" size={12}/>;
}

export function TabCloseIcon({dirty}: {dirty: boolean}) {
    return <Codicon name={dirty ? "circle-filled" : "close"} size={dirty ? 10 : 16}/>;
}

export function CloseIcon() {
    return <Codicon name="close"/>;
}

export function NotificationIcon({error}: {error: boolean}) {
    return <Codicon name={error ? "error" : "info"} class={error ? "text-red-400" : "text-sky-400"}/>;
}

export function AddChildIcon() {
    return <Codicon name="add" size={12}/>;
}

export function FileGlyph({path}: { path: string }) {
    const color = path.endsWith(".js") ? "text-yellow-300" : path.endsWith(".json") ? "text-amber-500" : "text-sky-300";
    return <Codicon name={path.endsWith(".json") ? "json" : path.endsWith(".js") ? "file-code" : "file"}
                    class={color}/>;
}
