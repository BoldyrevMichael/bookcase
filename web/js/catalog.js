/*
 * Каталог: поиск, отбор перечнями значений и продолжение с места остановки.
 *
 * Страница не хранит номер страницы — только курсор, который вернул сервис. Так задумано
 * в самом API: на глубоких страницах отсчёт «пропустить N» заставляет базу читать и
 * выбрасывать всё, что до них, а курсор от этого свободен.
 */
// Полка у книги ровно одна, поэтому и в отборе она одна: не набор значений, а выбор.
// Формат, тема и язык — наоборот, набор: книга бывает и про Java, и про базы данных.
const state = {
    q: '',
    sort: 'ADDED',
    format: [],
    theme: [],
    language: [],
    shelf: null,
    favorite: false,
    // Подборка приходит из адреса: со страницы подборок сюда ведёт ссылка «показать
    // в каталоге», и дальше отбор работает как любой другой.
    collection: null,
    cursor: null,
};
const books = document.getElementById('books');

mountHeader('/');
restoreFromUrl();
load(true);

document.getElementById('search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    state.q = document.getElementById('q').value.trim();
    state.sort = document.getElementById('sort').value;
    load(true);
});

document.getElementById('reset').addEventListener('click', () => {
    Object.assign(state, {
        q: '',
        sort: 'ADDED',
        format: [],
        theme: [],
        language: [],
        shelf: null,
        favorite: false,
        collection: null,
    });
    document.getElementById('q').value = '';
    document.getElementById('sort').value = 'ADDED';
    load(true);
});

document.getElementById('more').addEventListener('click', () => load(false));

function restoreFromUrl() {
    const params = new URLSearchParams(window.location.search);
    state.q = params.get('q') || '';
    state.sort = params.get('sort') || 'ADDED';
    for (const name of ['format', 'theme', 'language']) {
        state[name] = params.getAll(name);
    }
    state.shelf = params.get('shelf');
    state.favorite = params.get('favorite') === 'true';
    state.collection = params.get('collection');
    document.getElementById('q').value = state.q;
    document.getElementById('sort').value = state.sort;
}

/** Отбор виден в адресе: такой страницей можно поделиться и вернуться к ней потом. */
function rememberInUrl() {
    const params = new URLSearchParams();
    if (state.q) params.set('q', state.q);
    if (state.sort !== 'ADDED') params.set('sort', state.sort);
    for (const name of ['format', 'theme', 'language']) {
        state[name].forEach((value) => params.append(name, value));
    }
    if (state.shelf) params.set('shelf', state.shelf);
    if (state.favorite) params.set('favorite', 'true');
    if (state.collection) params.set('collection', state.collection);
    const query = params.toString();
    history.replaceState(null, '', query ? '?' + query : '/');
}

function query(withCursor) {
    const params = new URLSearchParams();
    if (state.q) params.set('q', state.q);
    // Порядок «по совпадению» имеет смысл только при запросе: без него совпадать не с чем.
    const meaningful = state.sort === 'RELEVANCE' ? 'ADDED' : state.sort;
    params.set('sort', state.q ? state.sort : meaningful);
    for (const name of ['format', 'theme', 'language']) {
        state[name].forEach((value) => params.append(name, value));
    }
    if (state.shelf) params.set('shelf', state.shelf);
    if (state.favorite) params.set('favorite', 'true');
    if (state.collection) params.set('collection', state.collection);
    params.set('limit', '24');
    if (withCursor && state.cursor) params.set('cursor', state.cursor);
    return params.toString();
}

document.getElementById('drop-collection').addEventListener('click', () => {
    state.collection = null;
    load(true);
});

/** Название подборки показываем отдельно: по одному лишь идентификатору в адресе не понять. */
async function showCollection() {
    const box = document.getElementById('collection-filter');
    box.classList.toggle('hidden', !state.collection);
    if (!state.collection) return;
    try {
        const collections = await api('/api/v1/collections');
        const chosen = collections.find((collection) => collection.id === state.collection);
        document.getElementById('collection-name').textContent = chosen
            ? 'подборка: ' + chosen.name
            : 'подборка';
    } catch {
        // Название — украшение; отбор работает и без него.
    }
}

async function load(fromStart) {
    if (fromStart) {
        state.cursor = null;
        books.innerHTML = '<p class="note">Ищем…</p>';
    }
    rememberInUrl();
    try {
        const page = await api('/api/v1/books?' + query(!fromStart));
        if (fromStart) books.innerHTML = '';
        page.items.forEach((book) => books.append(render(book)));
        state.cursor = page.nextCursor;
        document.getElementById('more').classList.toggle('hidden', !page.nextCursor);
        showFacets(page.facets);
        if (!books.children.length) {
            books.innerHTML = '<p class="empty">Ничего не нашлось. Попробуйте другое слово или снимите отбор.</p>';
        }
        summary(page);
        showCollection();
    } catch (error) {
        showError(books, error.message);
    }
}

function summary(page) {
    const shown = books.querySelectorAll('.book').length;
    const total = (page.facets.formats || []).reduce((sum, value) => sum + value.count, 0);
    document.getElementById('summary').textContent =
        shown ? `Показано ${shown} из ${total}` : '';
}

function render(book) {
    const card = document.createElement('a');
    card.className = 'book';
    card.href = '/book.html?id=' + encodeURIComponent(book.id);
    // Обложка приходит по идентификатору карточки: адреса картинки в хранилище
    // у страницы нет, и владение проверяется на каждый показ.
    const cover = book.hasCover
        ? `<img src="/api/v1/books/${encodeURIComponent(book.id)}/cover" alt="" loading="lazy">`
        : `<span class="format">${escapeHtml(book.format)}</span>`;
    card.innerHTML =
        `<div class="cover">${cover}</div>` +
        '<div class="about">' +
        `<span class="title">${escapeHtml(book.title || 'Без названия')}</span>` +
        `<span class="authors">${escapeHtml(authorsOf(book))}</span>` +
        `<span class="year">${book.year || ''}${book.favorite ? ' ★' : ''}</span>` +
        (book.status === 'NEEDS_REVIEW'
            ? '<span class="badge review">нужен просмотр</span>'
            : '') +
        '</div>';
    return card;
}

/** Перечни значений считаются по тому же отбору, поэтому показывают, что ещё найдётся. */
function showFacets(facets) {
    fill('facet-formats', 'format', facets.formats, (value) => value);
    fill('facet-themes', 'theme', facets.themes, (value) => value);
    fill('facet-languages', 'language', facets.languages, (value) => value);
    fillSingle('facet-shelves', 'shelf', facets.shelves, (value) => SHELVES[value] || value);
    const favorite = document.getElementById('only-favorite');
    favorite.setAttribute('aria-pressed', String(state.favorite));
}

document.getElementById('only-favorite').addEventListener('click', () => {
    state.favorite = !state.favorite;
    load(true);
});

/** Полка — один выбор: повторное нажатие снимает отбор. */
function fillSingle(containerId, name, values, label) {
    const container = document.getElementById(containerId);
    container.innerHTML = '';
    if (!values?.length) {
        container.innerHTML = '<p class="note">—</p>';
        return;
    }
    values.forEach((value) => {
        const chosen = state[name] === value.value;
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'facet';
        button.setAttribute('aria-pressed', String(chosen));
        button.innerHTML =
            `<span>${escapeHtml(label(value.value))}</span><span class="count">${value.count}</span>`;
        button.addEventListener('click', () => {
            state[name] = chosen ? null : value.value;
            load(true);
        });
        container.append(button);
    });
}

function fill(containerId, name, values, label) {
    const container = document.getElementById(containerId);
    container.innerHTML = '';
    if (!values?.length) {
        container.innerHTML = '<p class="note">—</p>';
        return;
    }
    values.forEach((value) => {
        const chosen = state[name].includes(value.value);
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'facet';
        button.setAttribute('aria-pressed', String(chosen));
        button.innerHTML =
            `<span>${escapeHtml(label(value.value))}</span><span class="count">${value.count}</span>`;
        button.addEventListener('click', () => {
            state[name] = chosen
                ? state[name].filter((chosenValue) => chosenValue !== value.value)
                : [...state[name], value.value];
            load(true);
        });
        container.append(button);
    });
}
