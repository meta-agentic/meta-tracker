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
}

/** A bulk payload as it would arrive from the reactive REST/SSE edge. */
export interface WorkspaceSnapshot {
  boards: Board[];
  epics: Epic[];
  issues: Issue[];
}
