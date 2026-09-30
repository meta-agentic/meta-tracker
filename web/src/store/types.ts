export type ID = string;

export interface Column {
  id: ID;
  name: string;
  order: number;
}

export interface Board {
  id: ID;
  key: string;
  name: string;
  columns: Column[];
}

export interface Epic {
  id: ID;
  key: string;
  title: string;
  color: string;
}

export interface Issue {
  id: ID;
  key: string;
  boardId: ID;
  epicId: ID | null;
  columnId: ID;
  title: string;
  order: number;
  startDate: string | null;
  dueDate: string | null;
  storyPoints: number | null;
  /** Work-item kind (story, bug, task, …) as the field document names it. */
  type: string | null;
  labels: string[];
  priority: string | null;
  /** Plain text. Rendered as-is, never as HTML. */
  description: string | null;
  /** Keys of items this one depends on. May name items the client never loaded. */
  dependsOn: string[];
  /** Keys of related items. May name items the client never loaded. */
  relates: string[];
}

/** The workspace the snapshot belongs to — what the app shell names. */
export interface WorkspaceInfo {
  key: string;
  name: string;
}

/** A bulk payload as it would arrive from the reactive REST/SSE edge. */
export interface WorkspaceSnapshot {
  /** Absent for generated workspaces, which belong to no server workspace. */
  workspace?: WorkspaceInfo | null;
  boards: Board[];
  epics: Epic[];
  issues: Issue[];
}
