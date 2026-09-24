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

function readAccessToken() {
  const hash = new URLSearchParams(window.location.hash.slice(1));
  const token = hash.get("token");
  if (token) {
    window.sessionStorage.setItem("epherolib.editor.token", token);
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    return token;
  }
  return window.sessionStorage.getItem("epherolib.editor.token");
}

export function App() {
  const [status, setStatus] = useState<ServerStatus>();
  const [error, setError] = useState<string>();
  const [token, setToken] = useState<string>();


  useEffect(() => {
    const token = readAccessToken();
    if (!token) {
      setError("Run /epherolib editor start in Minecraft, then open the link it provides.");
      return;
    }
    setToken(token);

    fetch("/api/status", { headers: { Authorization: `Bearer ${token}` } })
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
          {status && token ? (
            <WorkspaceEditor token={token} />
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
