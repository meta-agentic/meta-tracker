// SPDX-License-Identifier: Apache-2.0
import { useEffect, useRef, useState, type MouseEvent } from "react";
import { useTranslation } from "react-i18next";
import type { WorkspaceList } from "../api";
import { workspaceHref } from "../store/workspaceRoute";
import { BoardIcon, ChevronIcon, InboxIcon, RetryIcon, WarningIcon } from "./icons";

/** A click the page handles itself; any other (a new tab, a new window) is the browser's. */
function isPlainClick(event: MouseEvent) {
  return (
    event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey
  );
}

/**
 * The server's workspaces as links. Each one is a real `?ws=` address, so it
 * can be opened in a new tab or copied; a plain click switches in place.
 */
function WorkspaceLinks({
  list,
  current,
  onSwitch,
}: {
  list: WorkspaceList;
  current: string | null;
  onSwitch: (key: string) => void;
}) {
  const { t } = useTranslation();
  if (list.status === "loading") {
    return <p className="vec-muted-note vec-workspace-list__note">{t("workspace.listing")}</p>;
  }
  if (list.status === "error" || list.workspaces === null) {
    return (
      <div className="vec-workspace-list__note">
        <p>{t("workspace.listError")}</p>
        {list.error && <code className="vec-state__diagnostic">{list.error.message}</code>}
        {list.canRetry && (
          <button type="button" className="vec-button" onClick={list.retry}>
            <RetryIcon size={14} />
            {t("app.retry")}
          </button>
        )}
      </div>
    );
  }
  if (list.workspaces.length === 0) {
    return <p className="vec-muted-note vec-workspace-list__note">{t("workspace.none.title")}</p>;
  }
  return (
    <ul className="vec-workspace-list">
      {list.workspaces.map((workspace) => (
        <li key={workspace.key}>
          <a
            className="vec-workspace-list__link"
            href={workspaceHref(workspace.key)}
            aria-current={workspace.key === current ? "page" : undefined}
            onClick={(event) => {
              if (!isPlainClick(event)) return;
              event.preventDefault();
              onSwitch(workspace.key);
            }}
          >
            {/* Workspace names are user data, not chrome — deliberately not localized. */}
            <span className="vec-workspace-list__name">{workspace.name}</span>
            <span className="vec-key">{workspace.key}</span>
          </a>
        </li>
      ))}
    </ul>
  );
}

export interface WorkspaceSwitcherProps {
  /** The open workspace; its name is null until the store or the list has it. */
  current: { key: string; name: string | null } | null;
  list: WorkspaceList;
  onSwitch: (key: string) => void;
}

/** The shell's workspace: what is open, and a menu of the others. */
export function WorkspaceSwitcher({ current, list, onSwitch }: WorkspaceSwitcherProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDetailsElement>(null);
  const summaryRef = useRef<HTMLElement>(null);

  // A menu over the board closes like one: on a click elsewhere, and on Escape,
  // which hands focus back to where the menu was opened from.
  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      summaryRef.current?.focus();
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  return (
    <details
      ref={rootRef}
      className="vec-workspace"
      open={open}
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary ref={summaryRef} className="vec-workspace__summary">
        <span className="vec-sr">{t("workspace.switcher")}</span>
        {current ? (
          <>
            {/* Workspace names are user data, not chrome — deliberately not localized. */}
            {current.name !== null && <span className="vec-workspace__name">{current.name}</span>}
            <span className="vec-workspace__key">{current.key}</span>
          </>
        ) : (
          <span className="vec-workspace__name">{t("workspace.pick.title")}</span>
        )}
        <ChevronIcon size={14} className="vec-workspace__chevron" />
      </summary>
      {open && (
        <nav className="vec-workspace__menu" aria-label={t("workspace.list")}>
          <WorkspaceLinks
            list={list}
            current={current?.key ?? null}
            onSwitch={(key) => {
              setOpen(false);
              summaryRef.current?.focus();
              if (key !== current?.key) onSwitch(key);
            }}
          />
        </nav>
      )}
    </details>
  );
}

export interface WorkspacePickerProps {
  /** The workspace asked for that the server does not have, if any. */
  missing: string | null;
  list: WorkspaceList;
  onSwitch: (key: string) => void;
}

/** In place of a board when no workspace is open: the reason, and the way on. */
export function WorkspacePicker({ missing, list, onSwitch }: WorkspacePickerProps) {
  const { t } = useTranslation();
  const empty = list.status === "ready" && list.workspaces?.length === 0;

  return (
    <section className="vec-state vec-picker" aria-labelledby="vec-picker-title">
      {missing !== null ? (
        <>
          <span className="vec-state__icon vec-state__icon--danger">
            <WarningIcon size={22} />
          </span>
          <div role="alert">
            <h2 id="vec-picker-title" className="vec-state__title">
              {t("workspace.notFound.title", { key: missing })}
            </h2>
            <p className="vec-state__body">{t("workspace.notFound.body")}</p>
          </div>
        </>
      ) : (
        <>
          <span className="vec-state__icon">
            {empty ? <InboxIcon size={22} /> : <BoardIcon size={22} />}
          </span>
          <h2 id="vec-picker-title" className="vec-state__title">
            {t(empty ? "workspace.none.title" : "workspace.pick.title")}
          </h2>
          <p className="vec-state__body">
            {t(empty ? "workspace.none.body" : "workspace.pick.body")}
          </p>
        </>
      )}
      {!(empty && missing === null) && (
        <nav className="vec-picker__list" aria-label={t("workspace.list")}>
          <WorkspaceLinks list={list} current={null} onSwitch={onSwitch} />
        </nav>
      )}
    </section>
  );
}
