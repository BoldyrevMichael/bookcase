const SHELF_ORDER = ['READING', 'READ', 'POSTPONED', 'NONE'];
let shelf = 'READING';

mountHeader('/shelf.html');
drawShelfButtons();
loadShelf();
loadCollections();

function drawShelfButtons() {
    const row = document.getElementById('shelves');
    row.innerHTML = '';
    SHELF_ORDER.forEach((value) => {
        const button = document.createElement('button');
        button.textContent = SHELVES[value];
        if (value === shelf) button.className = 'primary';
        button.addEventListener('click', () => {
            shelf = value;
            drawShelfButtons();
            loadShelf();
        });
        row.append(button);
    });
}

async function loadShelf() {
    const container = document.getElementById('shelf-books');
    container.innerHTML = '<p class="note">Смотрим…</p>';
    try {
        const page = await api('/api/v1/books?limit=48&shelf=' + shelf);
        container.innerHTML = '';
        if (!page.items.length) {
            container.innerHTML = `<p class="empty">На этой полке пусто.</p>`;
            return;
        }
        page.items.forEach((book) => container.append(cardOf(book)));
    } catch (error) {
        showError(container, error.message);
    }
}

function cardOf(book) {
    const card = document.createElement('a');
    card.className = 'book';
    card.href = '/book.html?id=' + encodeURIComponent(book.id);
    const cover = book.hasCover
        ? `<img src="/api/v1/books/${encodeURIComponent(book.id)}/cover" alt="" loading="lazy">`
        : `<span class="format">${escapeHtml(book.format)}</span>`;
    card.innerHTML =
        `<div class="cover">${cover}</div>` +
        `<div class="about"><span class="title">${escapeHtml(book.title || 'Без названия')}</span>` +
        `<span class="authors">${escapeHtml(authorsOf(book))}</span></div>`;
    return card;
}

document.getElementById('new-collection').addEventListener('submit', async (event) => {
    event.preventDefault();
    const field = document.getElementById('collection-name');
    const name = field.value.trim();
    if (!name) return;
    try {
        await api('/api/v1/collections', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ name }),
        });
        field.value = '';
        document.getElementById('collection-error').textContent = '';
        loadCollections();
    } catch (error) {
        document.getElementById('collection-error').innerHTML =
            `<span class="error">${escapeHtml(error.message)}</span>`;
    }
});

async function loadCollections() {
    const body = document.querySelector('#collections tbody');
    body.innerHTML = '';
    try {
        const collections = await api('/api/v1/collections');
        document.getElementById('no-collections').classList.toggle('hidden', collections.length > 0);
        collections.forEach((collection) => {
            const row = document.createElement('tr');
            row.innerHTML =
                `<td>${escapeHtml(collection.name)}</td>` +
                `<td>${collection.bookCount}</td>` +
                '<td class="row" style="justify-content:flex-end"></td>';
            const openButton = document.createElement('button');
            openButton.textContent = 'Показать в каталоге';
            openButton.addEventListener('click', () => {
                window.location.href = '/?collection=' + encodeURIComponent(collection.id);
            });
            const deleteButton = document.createElement('button');
            deleteButton.className = 'danger';
            deleteButton.textContent = 'Удалить';
            deleteButton.addEventListener('click', async () => {
                // Удаляется список, а не книги: они остаются в библиотеке.
                if (!confirm(`Удалить подборку «${collection.name}»? Книги останутся.`)) return;
                await api('/api/v1/collections/' + encodeURIComponent(collection.id), {
                    method: 'DELETE',
                });
                loadCollections();
            });
            row.children[2].append(openButton, deleteButton);
            body.append(row);
        });
    } catch (error) {
        body.innerHTML = `<tr><td colspan="3" class="error">${escapeHtml(error.message)}</td></tr>`;
    }
}
