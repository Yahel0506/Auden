import { create } from 'zustand';

/**
 * Long background jobs the listener should see (the phone shows them as a progress notification):
 * finding lyrics for an album, and later identifying a music folder. A job is a title, a count and a
 * line of detail; the banner under the player shows the oldest one still on screen.
 */
export interface Job {
  id: string;
  title: string;
  done: number;
  total: number;
  /** What to say beside the count while running, and the result once finished. */
  detail: string;
  finished: boolean;
  /** Cancels the work; absent when the job cannot be stopped. */
  cancel?: () => void;
}

interface Jobs {
  jobs: Job[];
  start: (job: Pick<Job, 'id' | 'title' | 'total'> & { cancel?: () => void }) => void;
  update: (id: string, patch: Partial<Pick<Job, 'done' | 'total' | 'detail' | 'cancel'>>) => void;
  finish: (id: string, detail: string) => void;
  dismiss: (id: string) => void;
}

export const useJobs = create<Jobs>()((set) => ({
  jobs: [],
  start: (job) => set((s) => ({ jobs: [...s.jobs.filter((j) => j.id !== job.id), { ...job, done: 0, detail: '', finished: false }] })),
  update: (id, patch) => set((s) => ({ jobs: s.jobs.map((j) => (j.id === id ? { ...j, ...patch } : j)) })),
  finish: (id, detail) => set((s) => ({ jobs: s.jobs.map((j) => (j.id === id ? { ...j, detail, finished: true, done: j.total, cancel: undefined } : j)) })),
  dismiss: (id) => set((s) => ({ jobs: s.jobs.filter((j) => j.id !== id) })),
}));
