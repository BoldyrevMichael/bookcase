/*
 * Карточка книги: что известно, откуда это известно и что можно поправить.
 *
 * Происхождение каждого поля показано рядом со значением. Это не украшение: система обещает
 * не трогать правки человека и не переписывать вычитанное из файла, и обещание должно быть
 * видно — иначе непонятно, почему уточнение из справочника одно поле заполнило, а другое нет.
 */
const id = new URLSearchParams(window.location.search).get('id');
const content = document.getElementById('content');
let book = null;

mountHeader('');
open();

async function open() {
    if (!id) {
        showError(content, 'не указано, какую книгу открыть');
        return;
    }
    try {
        book = await api('/api/v1/books/' + encodeURIComponent(id));
        render();
    } catch (error) {
        showError(content, error.message);
    }
}

function render() {
    const cover = book.hasCover
        ? `<img src="/api/v1/books/${encodeURIComponent(book.id)}/cover" alt="Обложка">`
        : `<span class="note">${escapeHtml(book.format)}</span>`;

    content.innerHTML = `
        <div class="card">
            <div>
                <div class="cover">${cover}</div>
                <div class="row" style="margin-top:12px">
                    <button class="primary" id="download">Скачать</button>
                    <button class="danger" id="delete">Удалить</button>
                </div>
                <div class="row" style="margin-top:8px">
                    <button id="enrich">Уточнить заново</button>
                </div>
                <p class="note" id="enrich-note" style="margin-top:6px"></p>
                <p class="note" id="file-note" style="margin-top:10px"></p>
            </div>
            <div>
                <h1 style="margin-bottom:6px">${escapeHtml(book.title || 'Без названия')}</h1>
                <p class="note" style="margin-top:0">
                    ${escapeHtml(authorsOf(book) || 'автор неизвестен')}
                    · ${escapeHtml(STATUSES[book.status] || book.status)}
                </p>

                <dl class="props">
                    ${property('Год', book.year, 'year')}
                    ${property('Язык', book.language, 'language')}
                    ${property('ISBN', book.isbn, 'isbn')}
                    ${property('Серия', seriesOf(book), 'series')}
                    ${property('Издательство', book.publisher, 'publisher')}
                    <dt>Файл</dt><dd>${escapeHtml(book.originalName)}</dd>
                </dl>

                <h2>Полка и избранное</h2>
                <div class="row">
                    <select id="shelf" style="width:auto">
                        ${Object.entries(SHELVES)
                            .map(
                                ([value, title]) =>
                                    `<option value="${value}"${book.shelf === value ? ' selected' : ''}>${title}</option>`,
                            )
                            .join('')}
                    </select>
                    <button id="favorite">${book.favorite ? '★ в избранном' : '☆ в избранное'}</button>
                </div>

                <h2>Темы</h2>
                <div class="chips" id="themes">
                    ${(book.themes || []).map((theme) => `<span class="chip">${escapeHtml(theme)}</span>`).join('') || '<span class="note">не проставлены</span>'}
                </div>

                <h2>Поправить</h2>
                <form id="edit" class="panel">
                    <p class="note" style="margin-top:0">
                        Пустое поле означает «не трогать». Исправленное вами больше не изменит
                        ни разбор файла, ни справочник.
                    </p>
                    <label class="field"><span>Название</span>
                        <input type="text" name="title" value="${escapeHtml(book.title || '')}"></label>
                    <label class="field"><span>Авторы через запятую</span>
                        <input type="text" name="authors" value="${escapeHtml(authorsOf(book))}"></label>
                    <div class="row">
                        <label class="field" style="flex:1"><span>Год</span>
                            <input type="number" name="year" value="${book.year || ''}"></label>
                        <label class="field" style="flex:1"><span>Язык</span>
                            <input type="text" name="language" value="${escapeHtml(book.language || '')}"></label>
                        <label class="field" style="flex:1"><span>ISBN</span>
                            <input type="text" name="isbn" value="${escapeHtml(book.isbn || '')}"></label>
                    </div>
                    <label class="field"><span>Издательство</span>
                        <input type="text" name="publisher" value="${escapeHtml(book.publisher || '')}"></label>
                    <label class="field"><span>Темы через запятую</span>
                        <input type="text" name="themes" value="${escapeHtml((book.themes || []).join(', '))}"></label>
                    <div class="row">
                        <button class="primary" type="submit">Сохранить</button>
                        <span class="note" id="saved"></span>
                    </div>
                </form>
            </div>
        </div>`;

    document.getElementById('download').addEventListener('click', download);
    document.getElementById('delete').addEventListener('click', remove);
    document.getElementById('shelf').addEventListener('change', changeShelf);
    document.getElementById('favorite').addEventListener('click', toggleFavorite);
    document.getElementById('edit').addEventListener('submit', save);
    document.getElementById('enrich').addEventListener('click', enrich);
}

function seriesOf(value) {
    if (!value.series) return null;
    return value.seriesNumber ? `${value.series}, № ${value.seriesNumber}` : value.series;
}

/** Значение вместе с пометкой, откуда оно взялось. */
function property(title, value, field) {
    if (value === null || value === undefined || value === '') return '';
    const source = book.sources ? book.sources[field] : null;
    const mark = source ? `<span class="source">${SOURCES[source] || source}</span>` : '';
    return `<dt>${title}</dt><dd>${escapeHtml(value)}${mark}</dd>`;
}

/** Ссылка живёт минуту и ведёт прямо в хранилище — скачивание идёт мимо каталога. */
async function download() {
    const note = document.getElementById('file-note');
    try {
        const link = await api(`/api/v1/books/${encodeURIComponent(book.id)}/download`);
        note.textContent = 'Имя файла: ' + link.fileName;
        window.location.href = link.url;
    } catch (error) {
        note.innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`;
    }
}

/**
 * Просит справочники посмотреть книгу ещё раз.
 *
 * <p>Пригодится, когда карточка неполна: справочник мог не знать книгу раньше, мог ответить
 * не тем, а мог быть недоступен ровно в те попытки, что ему отвели. Ответ придёт не сразу —
 * спрашивает фоновый работник, — поэтому здесь только сообщение о том, что просьба принята.
 */
async function enrich() {
    const note = document.getElementById('enrich-note');
    note.textContent = 'Просим справочники…';
    try {
        await api(`/api/v1/books/${encodeURIComponent(book.id)}/enrich`, { method: 'POST' });
        note.textContent = 'Принято. Карточка дополнится сама, когда придёт ответ — обновите страницу через минуту.';
    } catch (error) {
        note.innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`;
    }
}

async function remove() {
    if (!confirm('Удалить книгу из библиотеки? Файл тоже будет удалён.')) return;
    try {
        await api('/api/v1/books/' + encodeURIComponent(book.id), { method: 'DELETE' });
        window.location.href = '/';
    } catch (error) {
        showError(content, error.message);
    }
}

async function changeShelf(event) {
    book = await api(
        `/api/v1/books/${encodeURIComponent(book.id)}/shelf?value=${event.target.value}`,
        { method: 'PUT' },
    );
}

async function toggleFavorite() {
    book = await api(
        `/api/v1/books/${encodeURIComponent(book.id)}/favorite?value=${!book.favorite}`,
        { method: 'PUT' },
    );
    document.getElementById('favorite').textContent = book.favorite
        ? '★ в избранном'
        : '☆ в избранное';
}

async function save(event) {
    event.preventDefault();
    const form = new FormData(event.target);
    const edit = {};
    const text = (name) => (form.get(name) || '').trim();
    if (text('title')) edit.title = text('title');
    if (text('publisher')) edit.publisher = text('publisher');
    if (text('language')) edit.language = text('language');
    if (text('isbn')) edit.isbn = text('isbn');
    if (text('year')) edit.year = Number(text('year'));
    if (text('authors')) edit.authors = split(text('authors'));
    if (text('themes')) edit.themes = split(text('themes'));

    try {
        book = await api('/api/v1/books/' + encodeURIComponent(book.id), {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(edit),
        });
        render();
        document.getElementById('saved').textContent = 'Сохранено';
    } catch (error) {
        document.getElementById('saved').innerHTML =
            `<span class="error">${escapeHtml(error.message)}</span>`;
    }
}

function split(value) {
    return value
        .split(',')
        .map((part) => part.trim())
        .filter(Boolean);
}
