import * as monaco from "monaco-editor/editor";
import "monaco-editor/features/register.all";
import "monaco-editor/languages/definitions/javascript/register";
import { getWorker, jsonDefaults, type MatchingSchema } from "monaco-editor/languages/features/json/register";
import { CompletionAdapter } from "monaco-editor/languages/features/json/jsonMode.js";
import "monaco-editor/languages/features/typescript/register";
import EditorWorker from "../../node_modules/monaco-editor/esm/vs/editor/editor.worker.js?worker";
import JsonWorker from "../../node_modules/monaco-editor/esm/vs/language/json/json.worker.js?worker";
import TypeScriptWorker from "../../node_modules/monaco-editor/esm/vs/language/typescript/ts.worker.js?worker";

self.MonacoEnvironment = {
  getWorker(_workerId: string, label: string) {
    if (label === "json") {
      return new JsonWorker();
    }
    if (label === "typescript" || label === "javascript") {
      return new TypeScriptWorker();
    }
    return new EditorWorker();
  }
};

jsonDefaults.setDiagnosticsOptions({validate: true, allowComments: false, schemas: []});
jsonDefaults.setModeConfiguration({...jsonDefaults.modeConfiguration, completionItems: false});

const jsonWorker = async (uri: monaco.Uri) => (await getWorker())(uri);
const completionAdapter = new CompletionAdapter(jsonWorker, [" ", ":", '"']);

let completionsRegistered = false;

export function registerWorkspaceCompletions() {
  if (completionsRegistered) return;
  completionsRegistered = true;
  monaco.languages.registerCompletionItemProvider("json", {
    triggerCharacters: completionAdapter.triggerCharacters,
    async provideCompletionItems(model, position, context, token) {
      const completions = await completionAdapter.provideCompletionItems(model, position, context, token);
      if (!completions || token.isCancellationRequested) return completions;

      try {
        const worker = await jsonWorker(model.uri);
        const matches = await worker.getMatchingSchemas(model.uri.toString());
        const keys = schemaKeysAtPosition(matches, model.getOffsetAt(position));
        for (const suggestion of completions.suggestions) {
          const label = typeof suggestion.label === "string" ? suggestion.label : suggestion.label.label;
          if (suggestion.kind === monaco.languages.CompletionItemKind.Property && keys.has(label)) {
            suggestion.kind = monaco.languages.CompletionItemKind.Field;
            suggestion.label = {label, description: "Schema key"};
          }
        }
      } catch {
        // Keep Monaco's original completions if schema inspection is unavailable.
      }

      return completions;
    }
  });
}

function schemaKeysAtPosition(matches: MatchingSchema[], offset: number): Set<string> {
  const objects = matches.filter(match =>
    match.node.type === "object" && match.node.offset <= offset && offset <= match.node.offset + match.node.length
  );
  if (objects.length === 0) return new Set();
  const smallestObject = Math.min(...objects.map(match => match.node.length));
  const keys = new Set<string>();
  for (const match of objects) {
    if (match.node.length === smallestObject) {
      for (const key of Object.keys(match.schema.properties ?? {})) keys.add(key);
    }
  }
  return keys;
}

export interface JsonSchemaRegistration {
  uri: string;
  fileMatch: string[];
  schema: object;
}

let recipeSchema: object | undefined;
let tagSchemas: JsonSchemaRegistration[] = [];
let dataPackSchemas: JsonSchemaRegistration[] = [];

function applyJsonSchemas() {
  const schemas: JsonSchemaRegistration[] = [...tagSchemas, ...dataPackSchemas];
  if (recipeSchema) {
    schemas.push({
      uri: "datapack-editor://schemas/recipes.json",
      fileMatch: [
        "file:///**/data/*/recipe/*.json",
        "file:///**/data/*/recipe/**/*.json",
        "file:///**/data/*/recipes/*.json",
        "file:///**/data/*/recipes/**/*.json"
      ],
      schema: recipeSchema
    });
  }
  jsonDefaults.setDiagnosticsOptions({validate: true, allowComments: false, schemas});
}

export function configureRecipeSchema(schema: object) {
  recipeSchema = schema;
  applyJsonSchemas();
}

export function configureTagSchemas(schemas: JsonSchemaRegistration[]) {
  tagSchemas = schemas;
  applyJsonSchemas();
}

export function configureDataPackSchemas(schemas: JsonSchemaRegistration[]) {
  dataPackSchemas = schemas;
  applyJsonSchemas();
}

export { monaco };
