import {createPortal} from "preact/compat";
import {useEffect, useMemo, useRef, useState} from "preact/hooks";

export interface AdvancementCompletions {
    advancements: string[];
    items: string[];
    triggers: string[];
    backgrounds: string[];
    recipes: string[];
    lootTables: string[];
    functions: string[];
}

interface AutocompleteInputProps {
    value: string;
    options: string[];
    onChange: (value: string) => void;
    className: string;
    placeholder?: string;
    required?: boolean;
    onBlur?: () => void;
}

export function AutocompleteInput({value, options, onChange, className, placeholder, required, onBlur}: AutocompleteInputProps) {
    const input = useRef<HTMLInputElement>(null);
    const list = useRef<HTMLDivElement>(null);
    const [open, setOpen] = useState(false);
    const [active, setActive] = useState(-1);
    const [position, setPosition] = useState<{top: number; left: number; width: number; maxHeight: number}>();
    const matches = useMemo(() => {
        const query = value.trim().toLowerCase();
        return options.filter(option => option !== value && option.toLowerCase().includes(query));
    }, [options, value]);
    useEffect(() => {
        if (!open || !matches.length) return;
        const update = () => {
            const rect = input.current?.getBoundingClientRect();
            if (!rect) return;
            const below = window.innerHeight - rect.bottom - 8;
            const above = rect.top - 8;
            const showAbove = below < 160 && above > below;
            const maxHeight = Math.max(0, Math.min(224, (showAbove ? above : below) - 4));
            setPosition({
                top: showAbove ? Math.max(4, rect.top - maxHeight - 4) : rect.bottom + 4,
                left: rect.left, width: rect.width,
                maxHeight
            });
        };
        update();
        window.addEventListener("resize", update);
        window.addEventListener("scroll", update, true);
        return () => {
            window.removeEventListener("resize", update);
            window.removeEventListener("scroll", update, true);
        };
    }, [open, matches.length, value]);
    useEffect(() => {
        if (active >= 0) list.current?.querySelectorAll('[role="option"]')[active]?.scrollIntoView({block: "nearest"});
    }, [active]);
    const choose = (option: string) => {
        onChange(option);
        setOpen(false);
        setActive(-1);
    };
    return <span class="relative block">
        <input ref={input} class={className} value={value} placeholder={placeholder} required={required} spellcheck={false}
               autocomplete="off" role="combobox" aria-autocomplete="list" aria-expanded={open && matches.length > 0}
               onFocus={() => { setActive(-1); setOpen(true); }} onInput={event => {
                   const next = event.currentTarget.value;
                   onChange(next);
                   setActive(0);
                   setOpen(true);
               }} onBlur={() => {
                   setOpen(false);
                   onBlur?.();
               }} onKeyDown={event => {
                   if (event.key === "Escape") setOpen(false);
                   if (event.key === "ArrowDown" && matches.length) {
                       event.preventDefault();
                       setOpen(true);
                       setActive(index => index < 0 ? 0 : (index + 1) % matches.length);
                   }
                   if (event.key === "ArrowUp" && matches.length) {
                       event.preventDefault();
                       setOpen(true);
                       setActive(index => index < 0 ? matches.length - 1 : (index + matches.length - 1) % matches.length);
                   }
                   if (event.key === "Enter" && open && active >= 0 && matches.length) {
                       event.preventDefault();
                       choose(matches[Math.min(active, matches.length - 1)]);
                   }
               }}/>
        {open && matches.length > 0 && position && createPortal(<div ref={list} role="listbox"
            style={{position: "fixed", top: position.top, left: position.left, width: position.width,
                maxHeight: position.maxHeight, zIndex: 1000}}
            class="overflow-y-auto rounded border border-white/20 bg-[#252526] py-1 shadow-xl shadow-black/50"
            onClick={event => event.stopPropagation()}>
            {matches.map((option, index) => <button key={option} type="button" role="option"
                aria-selected={index === active} title={option}
                class={`block w-full truncate px-2 py-1 text-left text-[11px] ${index === active ? "bg-violet-700 text-white" : "text-zinc-200 hover:bg-white/10"}`}
                onMouseDown={event => event.preventDefault()} onClick={() => choose(option)}>{option}</button>)}
        </div>, input.current?.closest('[role="dialog"]') ?? document.body)}
    </span>;
}
