/** The folder the listener chose: its handle (structured-cloned into IndexedDB, like a library root) and the layout. */
export interface FolderRecord {
  handle: FileSystemDirectoryHandle;
  name: string;
  template: string;
  chosenAt: number;
}
