export function FileGlyph({path}: { path: string }) {
    const color = path.endsWith(".js") ? "text-yellow-300" : path.endsWith(".json") ? "text-amber-500" : "text-sky-300";
    return (
        <svg class={`shrink-0 ${color}`} width="14" height="14" viewBox="0 0 16 16" fill="currentColor"
             aria-hidden="true">
            <path d="M3 1.5h6l4 4v9H3zm6 .9v3.8h3.8" fill-rule="evenodd"/>
        </svg>
    );
}
