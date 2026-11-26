import { useEffect, useState, type ReactNode } from "react";
import type { WorkspaceApiClient } from "../api";
import type { Effective } from "./rules";
import { ItemWritesContext } from "./itemWritesContext";

export function ItemWritesProvider({
  client,
  refresh,
  children,
}: {
  client: WorkspaceApiClient;
  refresh: () => void;
  children: ReactNode;
}) {
  const [loaded, setLoaded] = useState<{ configuration: Effective | null; readOnly: boolean | null }>({
    configuration: null,
    readOnly: null,
  });

  useEffect(() => {
    const controller = new AbortController();
    void Promise.allSettled([client.getWorkspace(controller.signal), client.getConfiguration(controller.signal)]).then(
      ([workspace, configuration]) => {
        if (controller.signal.aborted) return;
        setLoaded({
          readOnly: workspace.status === "fulfilled" ? workspace.value.readOnly : null,
          configuration:
            configuration.status === "fulfilled" ? (configuration.value.effective as unknown as Effective) : null,
        });
      },
    );
    return () => controller.abort();
  }, [client]);

  return <ItemWritesContext.Provider value={{ client, refresh, ...loaded }}>{children}</ItemWritesContext.Provider>;
}
