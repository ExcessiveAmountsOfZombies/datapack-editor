import { useEffect, useState } from "preact/hooks";
import { WorkspaceEditor } from "./workspace/WorkspaceEditor";
import { stopRenderingAssets } from "./workspace/preview/modelRenderer";

export function App() {
  const [error, setError] = useState<string>();
  useEffect(() => {
    const timer = setInterval(() => {
      const memory = (performance as Performance & {memory?: {usedJSHeapSize: number; jsHeapSizeLimit: number}}).memory;
      if (!memory || memory.usedJSHeapSize < Math.min(256 * 1024 * 1024, memory.jsHeapSizeLimit / 4)) return;
      clearInterval(timer);
      stopRenderingAssets();
      setError("Editor stopped because the browser reached its memory budget. Restart the editor after closing this tab; unsaved edits may be lost.");
      void fetch("/api/editor-stop", {method: "POST", keepalive: true}).catch(() => undefined);
    }, 250);
    return () => clearInterval(timer);
  }, []);

  return (
    <main class="h-dvh overflow-hidden bg-[#09090b] text-zinc-100">
      <div class="flex h-full min-h-0 flex-col">
        <header class="h-8 shrink-0 border-b border-white/10 bg-[#181818]" />

        <div class="flex min-h-0 flex-1">
          {!error ? (
            <WorkspaceEditor />
          ) : (
            <section class="flex min-w-0 flex-1 items-center justify-center bg-zinc-950 px-4 text-sm text-zinc-500" role="status">
              {error}
            </section>
          )}
        </div>
      </div>
    </main>
  );
}
