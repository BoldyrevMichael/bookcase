/*
 * Общее для всех страниц: обращения к API, шапка и разбор ответов.
 *
 * Токенов здесь нет и быть не может. Браузер входит через шлюз и получает cookie, недоступную
 * скриптам; шлюз сам прикладывает токен к запросу, когда передаёт его сервису. Поэтому весь
 * фронтенд — это разметка и вызовы fetch, а вся работа с входом остаётся снаружи.
 */

/** Запрос к API. Возвращает разобранный ответ или бросает понятную ошибку. */
async function api(path, options = {}) {
    const response = await fetch(path, {
        credentials: 'same-origin',
        redirect: 'follow',
        ...options,
    });

    // Сессия кончилась — уводим на вход. Шлюзу для путей /api/ велено отвечать отказом,
    // а не переводом на страницу входа: страницу открывает человек, и его надо вести на вход,
    // а запрос из скрипта ждёт данных и получил бы разметку формы как «успешный» ответ.
    // Проверка на разметку оставлена запасной: она ловит тот же случай, если настройка шлюза
    // однажды разойдётся с этим кодом.
    const type = response.headers.get('content-type') || '';
    if (response.status === 401 || response.status === 403) {
        signIn();
        throw new Error('нужен вход');
    }
    if (response.redirected && type.includes('text/html')) {
        signIn();
        throw new Error('нужен вход');
    }
    if (!response.ok) {
        throw new Error(await problem(response));
    }
    if (response.status === 204) {
        return null;
    }
    return type.includes('application/json') ? response.json() : response.blob();
}

/** Сервисы отвечают на отказ описанием проблемы — показываем его, а не «ошибка 400». */
async function problem(response) {
    try {
        const body = await response.json();
        return body.detail || body.title || `ошибка ${response.status}`;
    } catch {
        return `ошибка ${response.status}`;
    }
}

function signIn() {
    window.location.href = '/oauth2/start?rd=' + encodeURIComponent(window.location.pathname + window.location.search);
}

/** Шапка одинакова везде: имя вошедшего и выход. */
async function mountHeader(current) {
    const pages = [
        ['/', 'Каталог'],
        ['/upload.html', 'Загрузка'],
        ['/shelf.html', 'Полка и подборки'],
        ['/export.html', 'Выгрузка'],
    ];
    const header = document.createElement('header');
    header.className = 'top';
    header.innerHTML =
        '<a class="brand" href="/">bookcase</a>' +
        '<nav>' +
        pages
            .map(
                ([href, title]) =>
                    `<a href="${href}"${href === current ? ' aria-current="page"' : ''}>${title}</a>`,
            )
            .join('') +
        '</nav>' +
        '<span class="who"></span>' +
        '<button class="quiet" id="sign-out">Выйти</button>';
    document.body.prepend(header);
    document.getElementById('sign-out').addEventListener('click', () => {
        window.location.href = '/oauth2/sign_out';
    });

    try {
        const me = await api('/api/v1/me');
        header.querySelector('.who').textContent = me.username || me.id;
    } catch {
        // Не удалось узнать, кто вошёл, — страница всё равно работает: имя не главное.
    }
}

/** Экранирование: названия и авторы приходят из файлов, а там бывает что угодно. */
function escapeHtml(value) {
    return String(value ?? '').replace(
        /[&<>"']/g,
        (character) =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character],
    );
}

/** «Швец А., Иванов И.» — авторов может не быть вовсе, и это нормально. */
function authorsOf(book) {
    return book.authors?.length ? book.authors.join(', ') : '';
}

function showError(container, message) {
    container.innerHTML = `<p class="error">${escapeHtml(message)}</p>`;
}

/** Человеческие названия состояний: в API они английские, в интерфейсе — нет. */
const SHELVES = {
    NONE: 'не на полке',
    READING: 'читаю',
    READ: 'прочитано',
    POSTPONED: 'отложено',
};

const SOURCES = {
    EMBEDDED: 'из файла',
    FILENAME: 'из имени файла',
    EXTERNAL: 'из справочника',
    USER: 'правка',
};

const STATUSES = {
    READY: 'готова',
    NEEDS_REVIEW: 'нужен просмотр',
};
