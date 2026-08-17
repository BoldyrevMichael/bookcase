/*
 * Загрузка идёт в два шага, и это видно на странице.
 *
 * Сначала файл целиком уходит в хранилище и получает имя по содержимому. Затем разбору
 * сообщается, что появился такой файл, — и он берётся за работу в фоне. Байты передаются
 * ровно один раз: разбор потом заберёт их у хранилища сам.
 */
const rows = document.querySelector('#queue tbody');
const STAGES = {
    QUEUED: 'ждёт разбора',
    RUNNING: 'разбирается',
    SUCCEEDED: 'готово',
    FAILED: 'не вышло',
};

mountHeader('/upload.html');

document.getElementById('send').addEventListener('click', () => {
    const chosen = document.getElementById('files').files;
    if (chosen.length) send([...chosen]);
});

const drop = document.getElementById('drop');
drop.addEventListener('dragover', (event) => {
    event.preventDefault();
    drop.classList.add('over');
});
drop.addEventListener('dragleave', () => {
    drop.style.borderColor = 'var(--border)';
});
drop.addEventListener('drop', (event) => {
    event.preventDefault();
    drop.style.borderColor = 'var(--border)';
    if (event.dataTransfer.files.length) send([...event.dataTransfer.files]);
});

async function send(files) {
    document.getElementById('nothing').classList.add('hidden');
    // По одному файлу за раз: книга бывает на сотни мегабайт, и десять таких сразу
    // не ускорят загрузку, а лишь займут канал и память браузера.
    for (const file of files) {
        const row = addRow(file.name);
        try {
            const stored = await upload(file, row);
            if (stored.alreadyStored) {
                note(row, 'такой файл уже был — второй копии не появилось');
            }
            const task = await api('/api/v1/ingestions', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ sha256: stored.sha256, originalName: file.name }),
            });
            watch(task.id, row);
        } catch (error) {
            stage(row, 'не вышло');
            note(row, error.message, true);
        }
    }
}

/**
 * Отправка файла через XMLHttpRequest, а не fetch: только так видно ход загрузки,
 * а книга на сотни мегабайт идёт заметное время, и полоса здесь не украшение.
 */
function upload(file, row) {
    return new Promise((resolve, reject) => {
        const form = new FormData();
        form.append('file', file);
        const request = new XMLHttpRequest();
        request.open('POST', '/api/v1/files');
        request.upload.addEventListener('progress', (event) => {
            if (event.lengthComputable) {
                progress(row, event.loaded / event.total);
                stage(row, 'передаётся');
            }
        });
        request.addEventListener('load', () => {
            progress(row, 1);
            if (request.status >= 200 && request.status < 300) {
                stage(row, 'ждёт разбора');
                resolve(JSON.parse(request.responseText));
            } else if (request.status === 401 || request.status === 403) {
                signIn();
                reject(new Error('нужен вход'));
            } else {
                reject(new Error(describe(request)));
            }
        });
        request.addEventListener('error', () => reject(new Error('не удалось передать файл')));
        request.send(form);
    });
}

function describe(request) {
    try {
        const body = JSON.parse(request.responseText);
        return body.detail || body.title || `ошибка ${request.status}`;
    } catch {
        return `ошибка ${request.status}`;
    }
}

/** Разбор идёт в фоне, поэтому состояние приходится спрашивать. */
async function watch(taskId, row) {
    for (let attempt = 0; attempt < 300; attempt++) {
        await new Promise((resume) => setTimeout(resume, attempt < 10 ? 1000 : 3000));
        let task;
        try {
            task = await api('/api/v1/ingestions/' + encodeURIComponent(taskId));
        } catch (error) {
            note(row, error.message, true);
            return;
        }
        stage(row, STAGES[task.status] || task.status);
        if (task.status === 'SUCCEEDED') {
            const found = task.metadata || {};
            const title = found.title || 'без названия';
            const authors = (found.authors || []).join(', ');
            note(row, `${title}${authors ? ' — ' + authors : ''}`);
            return;
        }
        if (task.status === 'FAILED') {
            note(row, task.failureReason || 'разобрать не удалось', true);
            return;
        }
    }
    note(row, 'разбор идёт дольше обычного — загляните в каталог позже');
}

function addRow(name) {
    const row = document.createElement('tr');
    row.innerHTML =
        `<td>${escapeHtml(name)}<div class="progress" style="margin-top:6px"><div></div></div></td>` +
        '<td class="stage note">готовим</td>' +
        '<td class="note"></td>';
    rows.append(row);
    return row;
}

function stage(row, text) {
    row.querySelector('.stage').textContent = text;
}

function progress(row, share) {
    row.querySelector('.progress > div').style.width = Math.round(share * 100) + '%';
}

function note(row, text, isError) {
    const cell = row.children[2];
    cell.className = isError ? 'error' : 'note';
    cell.textContent = text;
}
