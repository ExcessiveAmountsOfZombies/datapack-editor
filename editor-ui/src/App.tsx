import { useEffect, useState } from "preact/hooks";
import { WorkspaceEditor } from "./workspace/WorkspaceEditor";

interface ServerStatus {
  name: string;
  state: string;
  host: string;
  port: number;
  security: string;
  workspace: string;
  features: string[];
}

export function App() {
  const [status, setStatus] = useState<ServerStatus>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    fetch("/api/status")
      .then(async response => {
        if (!response.ok) {
          throw new Error((await response.json()).error ?? `Request failed (${response.status})`);
        }
        return response.json() as Promise<ServerStatus>;
      })
      .then(setStatus)
      .catch(reason => setError(reason instanceof Error ? reason.message : String(reason)));
  }, []);

  return (
    <main class="h-dvh overflow-hidden bg-[#09090b] text-zinc-100">
      <div class="flex h-full min-h-0 flex-col">
        <header class="h-8 shrink-0 border-b border-white/10 bg-[#181818]" />

        <div class="flex min-h-0 flex-1">
          {status ? (
            <WorkspaceEditor />
          ) : (
            <section class="flex min-w-0 flex-1 items-center justify-center bg-zinc-950 px-4 text-sm text-zinc-500" role="status">
              {error ?? "Connecting to the editor server…"}
            </section>
          )}
        </div>
      </div>
    </main>
  );
}
