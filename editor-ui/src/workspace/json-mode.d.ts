declare module "monaco-editor/languages/features/json/jsonMode.js" {
  import type * as monaco from "monaco-editor/editor";

  export class CompletionAdapter {
    constructor(worker: (uri: monaco.Uri) => Promise<unknown>, triggerCharacters: string[]);
    readonly triggerCharacters: string[];
    provideCompletionItems(
      model: monaco.editor.ITextModel,
      position: monaco.Position,
      context: monaco.languages.CompletionContext,
      token: monaco.CancellationToken
    ): Promise<monaco.languages.CompletionList | undefined>;
  }
}
