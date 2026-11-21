import { t as translateUi } from '@/i18n';
import { Banner, bannerButtonClass } from '@/shell/Banner';
import { useJobs } from './jobs';

/** Progress of the newest background job, under the player; a finished one stays until dismissed. */
export function JobBanner() {
  // The newest: a job started later (a lyrics run while a folder is being identified) must not hide behind the first.
  const job = useJobs((s) => s.jobs[s.jobs.length - 1]);
  const dismiss = useJobs((s) => s.dismiss);
  if (!job) return null;
  const progress = job.finished ? '' : job.total ? translateUi('job_banner.progress', { done: job.done, total: job.total }) : '';
  const detail = [progress, job.detail].filter(Boolean).join(' · ') || translateUi('job_banner.starting');
  return (
    <Banner
      title={job.title}
      detail={detail}
      action={
        job.finished ? (
          <button className={bannerButtonClass} onClick={() => dismiss(job.id)}>{translateUi("job_banner.done")}</button>
        ) : job.cancel ? (
          <button className={bannerButtonClass} onClick={job.cancel}>{translateUi("job_banner.stop")}</button>
        ) : null
      }
    />
  );
}
