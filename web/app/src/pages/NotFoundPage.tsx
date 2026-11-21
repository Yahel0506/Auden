import { t as translateUi } from '@/i18n';
import { PageHeader } from '@/components/PageHeader';
import { PrefetchLink } from '@/components/PrefetchLink';

export default function NotFoundPage() {
  return (
    <>
      <PageHeader title={translateUi("not_found_page.page_not_found")} />
      <p className="mx-(--body-gutter) my-2 type-title-3 text-secondary">
        {translateUi('not_found_page.this_page_doesnt_exist')} <PrefetchLink to="/home" className="text-key hover:underline">{translateUi("not_found_page.go_to_home")}</PrefetchLink>
      </p>
    </>
  );
}
