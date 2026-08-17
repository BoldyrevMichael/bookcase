/*
 * Выгрузка — единственное место, где страница живёт дольше запроса: архив собирает другой
 * сервис, и узнать о готовности можно только спросив. Идентификатор задачи остаётся в адресе,
 * чтобы к ней можно было вернуться после закрытия вкладки.
 */
const STAGES = {
    QUEUED: 'ждёт очереди',
    RUNNING: 'собирается',
    SUCCEEDED: 'готов',
    FAILED: 'не собрался',
};
let taskId = new URLSearchParams(window.location.search).get('task');
let timer = null;

mountHeader('/export.html');
if (taskId) refresh();

document.getElementById('start').addEventListener('click', async () => {
    const note = document.getElementById('start-note');
    note.textContent = 'Заводим задачу…';
    try {
        const task = await api('/api/v1/exports', { method: 'POST' });
        taskId = task.id;
        history.replaceState(null, '', '?task=' + encodeURIComponent(taskId));
        note.textContent = '';
        show(task);
        watch();
    } catch (error) {
        note.innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`;
    }
});

document.getElementById('refresh').addEventListener('click', refresh);

async function refresh() {
    try {
        show(await api('/api/v1/exports/' + encodeURIComponent(taskId)));
    } catch (error) {
        document.getElementById('task-note').innerHTML =
            `<span class="error">${escapeHtml(error.message)}</span>`;
    }
}

function watch() {
    clearInterval(timer);
    timer = setInterval(refresh, 3000);
}

function show(task) {
    document.getElementById('task').classList.remove('hidden');
    document.getElementById('task-status').textContent = STAGES[task.status] || task.status;
    document.getElementById('task-count').textContent = task.bookCount;
    document.getElementById('task-created').textContent = new Date(task.createdAt).toLocaleString('ru-RU');

    const download = document.getElementById('download');
    const ready = task.status === 'SUCCEEDED' && task.downloadUrl;
    download.classList.toggle('hidden', !ready);
    if (ready) {
        clearInterval(timer);
        download.onclick = () => {
            // Ссылка подписана и живёт минуту — открываем сразу, а не сохраняем на будущее.
            window.location.href = task.downloadUrl;
        };
    }
    if (task.status === 'FAILED') {
        clearInterval(timer);
        document.getElementById('task-note').innerHTML =
            `<span class="error">${escapeHtml(task.failureReason || 'собрать архив не удалось')}</span>`;
    } else if (task.status !== 'SUCCEEDED') {
        watch();
    }
}
