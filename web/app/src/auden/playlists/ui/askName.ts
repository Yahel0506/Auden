/** A small modal asking for a name (new playlist, rename). Resolves null when cancelled. Plain DOM, so any handler can await it. */
export function askName(title: string, initial = '', confirm = 'Save'): Promise<string | null> {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.setAttribute('aria-label', title);
    dialog.className = 'm-auto w-[min(380px,calc(100vw-32px))] rounded-2xl bg-[var(--systemQuaternary,#222)] p-5 text-primary backdrop:bg-black/50';
    const form = document.createElement('form');
    form.method = 'dialog';
    form.className = 'flex flex-col gap-3';
    const h = document.createElement('h2');
    h.className = 'type-title-3-emphasized';
    h.textContent = title;
    const input = document.createElement('input');
    input.className = 'h-9 rounded-lg bg-quinary px-3 type-body text-primary outline-none';
    input.value = initial;
    input.maxLength = 120;
    input.setAttribute('aria-label', 'Name');
    const row = document.createElement('div');
    row.className = 'flex justify-end gap-4';
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'type-callout text-secondary';
    cancel.textContent = 'Cancel';
    const ok = document.createElement('button');
    ok.type = 'submit';
    ok.className = 'type-callout-emphasized text-key';
    ok.textContent = confirm;
    row.append(cancel, ok);
    form.append(h, input, row);
    dialog.append(form);
    let result: string | null = null;
    cancel.onclick = () => dialog.close();
    form.onsubmit = () => {
      result = input.value.trim() || null;
    };
    dialog.onclose = () => {
      dialog.remove();
      resolve(result);
    };
    document.body.append(dialog);
    dialog.showModal();
    input.select();
  });
}
