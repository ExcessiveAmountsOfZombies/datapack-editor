import {useEffect, useMemo, useState} from "preact/hooks";
import {type AdvancementCompletions, AutocompleteInput} from "./AdvancementAutocomplete.tsx";
import {
    changedComponent,
    componentInput,
    editAdvancement,
    type NewAdvancement,
    newAdvancementContent,
    readAdvancementDocument,
    workspaceAdvancementPath
} from "./advancementEditing.ts";

interface NewAdvancementFormProps {
    parent?: string;
    namespace: string;
    onCreate: (path: string, content: string) => Promise<void>;
    onCancel: () => void;
    completions: AdvancementCompletions;
}

interface AdvancementDetailsProps {
    content?: string;
    onContentChange: (content: string) => void;
    completions: AdvancementCompletions;
}

const fieldClass = "w-full rounded border border-white/15 bg-[#1d1d1d] px-2 py-1.5 text-zinc-100 outline-none focus:border-violet-400";
const labelClass = "block space-y-1 text-[11px] text-zinc-400";

export function NewAdvancementForm({parent, namespace, onCreate, onCancel, completions}: NewAdvancementFormProps) {
    const [details, setDetails] = useState<NewAdvancement>({
        namespace, name: "", title: "", description: "", icon: "minecraft:book", parent
    });
    const [creating, setCreating] = useState(false);
    const [error, setError] = useState<string>();
    const update = (key: keyof NewAdvancement, value: string) => setDetails(current => ({...current, [key]: value}));
    const submit = async (event: SubmitEvent) => {
        event.preventDefault();
        if (creating) return;
        try {
            const path = workspaceAdvancementPath(details.namespace, details.name);
            const content = newAdvancementContent(details);
            setCreating(true);
            setError(undefined);
            await onCreate(path, content);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : String(reason));
        } finally {
            setCreating(false);
        }
    };

    return <form class="space-y-3" onSubmit={submit}>
        <p class="text-[11px] text-zinc-400">A new workspace JSON file opens after creation. Its starter criterion uses
            <code class="ml-1 text-zinc-200">minecraft:impossible</code>; change it below or in JSON when ready.</p>
        <label class={labelClass}>Namespace
            <input class={fieldClass} value={details.namespace} required spellcheck={false}
                   onInput={event => update("namespace", event.currentTarget.value)}/>
        </label>
        <label class={labelClass}>Advancement path (inside advancement/)
            <input class={fieldClass} value={details.name} required spellcheck={false} placeholder="story/new_goal"
                   onInput={event => update("name", event.currentTarget.value)}/>
        </label>
        {parent !== undefined && <label class={labelClass}>Parent advancement ID
            <AutocompleteInput className={fieldClass} value={details.parent ?? ""} required
                               options={completions.advancements} onChange={value => update("parent", value)}/>
        </label>}
        <label class={labelClass}>Title
            <input class={fieldClass} value={details.title} placeholder="Uses the file name when empty"
                   onInput={event => update("title", event.currentTarget.value)}/>
        </label>
        <label class={labelClass}>Description
            <textarea class={`${fieldClass} min-h-16 resize-y`} value={details.description}
                      onInput={event => update("description", event.currentTarget.value)}/>
        </label>
        <label class={labelClass}>Icon item ID
            <AutocompleteInput className={fieldClass} value={details.icon} required
                               options={completions.items} onChange={value => update("icon", value)}/>
        </label>
        {error && <p role="alert" class="text-amber-300">{error}</p>}
        <div class="flex gap-2">
            <button type="submit" disabled={creating}
                    class="rounded bg-violet-700 px-3 py-1.5 font-medium text-white hover:bg-violet-600 disabled:opacity-50">
                {creating ? "Creating…" : "Create and open"}
            </button>
            <button type="button" class="rounded px-3 py-1.5 text-zinc-300 hover:bg-white/10"
                    onClick={onCancel}>Cancel
            </button>
        </div>
    </form>;
}

function object(value: unknown): Record<string, unknown> {
    return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

function stringList(value: unknown): string {
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === "string").join(", ") : "";
}

function parseList(value: string): string[] {
    return value.split(",").map(item => item.trim()).filter(Boolean);
}

interface ListFieldProps {
    label: string;
    value: string;
    onApply: (value: string[]) => void;
    options: string[];
}

function ListField({label, value, onApply, options}: ListFieldProps) {
    const [draft, setDraft] = useState(value);
    useEffect(() => {
        setDraft(value);
    }, [value]);
    const tokens = draft.split(",");
    const current = tokens.at(-1)?.trim() ?? "";
    return <label class={labelClass}>{label}
        <AutocompleteInput className={fieldClass} value={draft}
                           options={options.filter(option => option.toLowerCase().includes(current.toLowerCase()))
                               .map(option => [...tokens.slice(0, -1).map(token => token.trim()).filter(Boolean), option].join(", "))}
                           onChange={setDraft} onBlur={() => onApply(parseList(draft))}/>
    </label>;
}

interface CriterionConditionsProps {
    name: string;
    conditions: unknown;
    onApply: (value: Record<string, unknown> | undefined) => void;
}

function CriterionConditions({name, conditions, onApply}: CriterionConditionsProps) {
    const serialized = JSON.stringify(conditions ?? {}, null, 2);
    const [draft, setDraft] = useState(serialized);
    const [error, setError] = useState<string>();
    useEffect(() => {
        setDraft(serialized);
    }, [serialized]);
    const apply = () => {
        try {
            const parsed: unknown = JSON.parse(draft || "{}");
            if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
                throw new Error("Conditions must be a JSON object.");
            }
            onApply(Object.keys(parsed).length ? parsed as Record<string, unknown> : undefined);
            setError(undefined);
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : String(reason));
        }
    };
    return <details class="mt-2 text-[11px] text-zinc-400">
        <summary class="cursor-pointer">Conditions for {name}</summary>
        <textarea aria-label={`Conditions JSON for ${name}`} class={`${fieldClass} mt-2 min-h-24 font-mono`}
                  value={draft} spellcheck={false} onInput={event => setDraft(event.currentTarget.value)}/>
        {error && <p role="alert" class="mt-1 text-amber-300">{error}</p>}
        <button type="button" class="mt-2 rounded bg-white/10 px-2 py-1 text-zinc-200 hover:bg-white/20"
                onClick={apply}>Apply conditions
        </button>
    </details>;
}

export function AdvancementDetails({content, onContentChange, completions}: AdvancementDetailsProps) {
    const document = useMemo(() => readAdvancementDocument(content), [content]);
    const [criterionName, setCriterionName] = useState("");
    const [criterionError, setCriterionError] = useState<string>();
    if (!document || content === undefined) {
        return <p class="text-amber-300">Fix the advancement JSON to edit its details here.</p>;
    }
    const display = object(document.display);
    const icon = object(display.icon);
    const criteria = object(document.criteria);
    const rewards = object(document.rewards);
    const write = (change: (value: Record<string, unknown>) => void) => {
        const next = editAdvancement(content, change);
        if (next !== undefined) onContentChange(next);
    };
    const writeDisplay = (name: string, value: unknown) => write(data => {
        const next = {...object(data.display)};
        if (value === undefined) delete next[name];
        else next[name] = value;
        data.display = next;
    });
    const writeReward = (name: string, value: unknown) => write(data => {
        const next = {...object(data.rewards)};
        if (value === undefined || Array.isArray(value) && !value.length) delete next[name];
        else next[name] = value;
        if (Object.keys(next).length) data.rewards = next;
        else delete data.rewards;
    });
    const writeCriterion = (name: string, change: (criterion: Record<string, unknown>) => void) => write(data => {
        const next = {...object(data.criteria)};
        const criterion = {...object(next[name])};
        change(criterion);
        next[name] = criterion;
        data.criteria = next;
    });
    const addCriterion = () => {
        const name = criterionName.trim();
        if (!/^[a-zA-Z0-9_.-]+$/.test(name) || name in criteria) {
            setCriterionError("Choose a new criterion name using letters, numbers, underscores, periods, or hyphens.");
            return;
        }
        write(data => {
            data.criteria = {...object(data.criteria), [name]: {trigger: "minecraft:impossible"}};
            if (Array.isArray(data.requirements)) data.requirements = [...data.requirements, [name]];
        });
        setCriterionName("");
        setCriterionError(undefined);
    };
    const removeCriterion = (name: string) => write(data => {
        const next = {...object(data.criteria)};
        delete next[name];
        data.criteria = next;
        if (Array.isArray(data.requirements)) {
            data.requirements = data.requirements.map(group => Array.isArray(group)
                ? group.filter(value => value !== name) : group).filter(group => !Array.isArray(group) || group.length);
        }
    });

    return <div class="space-y-4">
        <div class="space-y-2">
            <h3 class="font-semibold text-zinc-200">Display</h3>
            <label class={labelClass}>Title or translation key
                <input class={fieldClass} value={componentInput(display.title)}
                       onInput={event => writeDisplay("title", changedComponent(display.title, event.currentTarget.value))}/>
            </label>
            <label class={labelClass}>Description or translation key
                <textarea class={`${fieldClass} min-h-16 resize-y`} value={componentInput(display.description)}
                          onInput={event => writeDisplay("description", changedComponent(display.description, event.currentTarget.value))}/>
            </label>
            <label class={labelClass}>Icon item ID
                <AutocompleteInput className={fieldClass} options={completions.items}
                                   value={typeof icon.id === "string" ? icon.id : typeof icon.item === "string" ? icon.item : ""}
                                   onChange={value => {
                                       const next: Record<string, unknown> = {...icon, id: value};
                                       delete next.item;
                                       writeDisplay("icon", next);
                                   }}/>
            </label>
            <label class={labelClass}>Frame
                <select class={fieldClass} value={typeof display.frame === "string" ? display.frame : "task"}
                        onChange={event => writeDisplay("frame", event.currentTarget.value)}>
                    <option value="task">Task</option>
                    <option value="goal">Goal</option>
                    <option value="challenge">Challenge</option>
                </select>
            </label>
            {!document.parent && <label class={labelClass}>Root background texture ID
                <AutocompleteInput className={fieldClass} options={completions.backgrounds}
                                   value={typeof display.background === "string" ? display.background : ""}
                                   placeholder="minecraft:gui/advancements/backgrounds/stone"
                                   onChange={value => writeDisplay("background", value || undefined)}/>
            </label>}
            {([["hidden", "Hidden"], ["show_toast", "Show toast"],
                ["announce_to_chat", "Announce to chat"]] as const).map(([key, title]) =>
                <label key={key} class="flex items-center gap-2 text-[11px] text-zinc-300">
                    <input type="checkbox" checked={typeof display[key] === "boolean" ? display[key] as boolean
                        : key !== "hidden"}
                           onChange={event => writeDisplay(key, event.currentTarget.checked)}/>{title}
                </label>)}
        </div>
        <div class="space-y-2 border-t border-white/10 pt-3">
            <h3 class="font-semibold text-zinc-200">Tree</h3>
            <label class={labelClass}>Parent advancement ID (empty for a root)
                <AutocompleteInput className={fieldClass} options={completions.advancements}
                                   value={typeof document.parent === "string" ? document.parent : ""}
                                   placeholder="example:story/root" onChange={value => write(data => {
                    const parent = value.trim();
                    if (parent) data.parent = parent;
                    else delete data.parent;
                })}/>
            </label>
        </div>
        <div class="space-y-2 border-t border-white/10 pt-3">
            <h3 class="font-semibold text-zinc-200">Criteria</h3>
            <p class="text-[11px] text-zinc-500">Added criteria are required individually. Edit requirements in JSON for
                OR groups.</p>
            {Object.entries(criteria).map(([name, value]) => {
                const criterion = object(value);
                return <div key={name} class="rounded border border-white/10 bg-[#1d1d1d] p-2">
                    <div class="flex items-center justify-between gap-2">
                        <span class="min-w-0 truncate font-medium text-zinc-200" title={name}>{name}</span>
                        <button type="button" disabled={Object.keys(criteria).length <= 1}
                                class="text-[11px] text-red-300 hover:text-red-200 disabled:opacity-30"
                                onClick={() => removeCriterion(name)}>Remove
                        </button>
                    </div>
                    <label class={`${labelClass} mt-2`}>Trigger ID
                        <AutocompleteInput className={fieldClass} options={completions.triggers}
                                           value={typeof criterion.trigger === "string" ? criterion.trigger : ""}
                                           onChange={value => writeCriterion(name, next => {
                                               next.trigger = value;
                                           })}/>
                    </label>
                    <CriterionConditions name={name} conditions={criterion.conditions}
                                         onApply={value => writeCriterion(name, next => {
                                             if (value) next.conditions = value;
                                             else delete next.conditions;
                                         })}/>
                </div>;
            })}
            <div class="flex gap-2">
                <input aria-label="New criterion name" class={fieldClass} placeholder="new_criterion"
                       value={criterionName} onInput={event => setCriterionName(event.currentTarget.value)}/>
                <button type="button" class="shrink-0 rounded bg-white/10 px-2 text-zinc-200 hover:bg-white/20"
                        onClick={addCriterion}>Add
                </button>
            </div>
            {criterionError && <p role="alert" class="text-amber-300">{criterionError}</p>}
        </div>
        <div class="space-y-2 border-t border-white/10 pt-3">
            <h3 class="font-semibold text-zinc-200">Rewards</h3>
            <label class={labelClass}>Experience points
                <input type="number" min="0" class={fieldClass}
                       value={typeof rewards.experience === "number" ? rewards.experience : ""}
                       onInput={event => writeReward("experience", event.currentTarget.value === ""
                           ? undefined : Number(event.currentTarget.value))}/>
            </label>
            <ListField label="Recipe IDs (comma separated)" value={stringList(rewards.recipes)}
                       options={completions.recipes}
                       onApply={value => writeReward("recipes", value)}/>
            <ListField label="Loot table IDs (comma separated)" value={stringList(rewards.loot)}
                       options={completions.lootTables}
                       onApply={value => writeReward("loot", value)}/>
            <label class={labelClass}>Function ID
                <AutocompleteInput className={fieldClass} options={completions.functions}
                                   value={typeof rewards.function === "string" ? rewards.function : ""}
                                   onChange={value => writeReward("function", value || undefined)}/>
            </label>
        </div>
    </div>;
}
