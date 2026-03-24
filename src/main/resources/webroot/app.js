/* ═══════════════════════════════════════════════════════
   STATE
════════════════════════════════════════════════════════ */
const API = '';
let token         = localStorage.getItem('np_token');
let currentUser   = localStorage.getItem('np_user') || '';
let currentNoteId = null;
let isDirty       = false;
let saveTimeout   = null;

/* ═══════════════════════════════════════════════════════
   INIT
════════════════════════════════════════════════════════ */
document.addEventListener('DOMContentLoaded', () => {
    if (token) {
        initApp();
        showPage('app');
    } else {
        showPage('login');
    }

    // Ctrl+S / Cmd+S to save
    document.addEventListener('keydown', e => {
        if ((e.ctrlKey || e.metaKey) && e.key === 's') {
            e.preventDefault();
            const title = document.getElementById('note-title').value.trim();
            if (title && isDirty) saveNote();
        }
        if (e.key === 'Escape') {
            document.getElementById('confirm-modal').style.display = 'none';
        }
    });

    // Poll queue status every 2 seconds while logged in
    setInterval(() => {
        if (token) checkQueueStatus();
    }, 2000);
});

/* ═══════════════════════════════════════════════════════
   PAGE NAVIGATION
════════════════════════════════════════════════════════ */
function showPage(name) {
    const all = document.querySelectorAll('.page');
    all.forEach(p => {
        p.classList.remove('active', 'exit');
        p.classList.add('exit');
    });

    setTimeout(() => {
        all.forEach(p => p.classList.remove('exit'));
        const target = document.getElementById(`page-${name}`);
        if (target) target.classList.add('active');
    }, 50);
}

/* ═══════════════════════════════════════════════════════
   TOAST SYSTEM
════════════════════════════════════════════════════════ */
function toast(message, type = 'success', duration = 3500) {
    const container = document.getElementById('toast-container');

    const el = document.createElement('div');
    el.className = `toast toast-${type}`;
    el.innerHTML = `<span class="toast-dot"></span><span>${message}</span>`;
    container.appendChild(el);

    requestAnimationFrame(() => {
        requestAnimationFrame(() => el.classList.add('visible'));
    });

    setTimeout(() => {
        el.classList.add('hiding');
        el.classList.remove('visible');
        setTimeout(() => el.remove(), 400);
    }, duration);
}

/* ═══════════════════════════════════════════════════════
   AUTH — LOGIN
════════════════════════════════════════════════════════ */
async function login() {
    const email    = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;

    if (!email || !password) {
        toast('Please fill in all fields', 'warning');
        return;
    }

    const btn = document.querySelector('#page-login .btn-primary');
    setButtonLoading(btn, true, 'Signing in…');

    try {
        const res  = await fetch(`${API}/api/auth/login`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ email, password })
        });
        const data = await res.json();

        if (res.ok) {
            token       = data.token;
            currentUser = data.username;
            localStorage.setItem('np_token', token);
            localStorage.setItem('np_user',  currentUser);
            toast(`Welcome back, ${data.username} 👋`, 'success');
            initApp();
            showPage('app');
        } else {
            toast(data.message || 'Invalid email or password', 'error');
        }
    } catch (err) {
        toast('Connection error. Is the server running?', 'error');
    } finally {
        setButtonLoading(btn, false, 'Sign In');
    }
}

/* ═══════════════════════════════════════════════════════
   AUTH — SIGNUP
════════════════════════════════════════════════════════ */
async function signup() {
    const username = document.getElementById('signup-username').value.trim();
    const email    = document.getElementById('signup-email').value.trim();
    const password = document.getElementById('signup-password').value;

    if (!username || !email || !password) {
        toast('Please fill in all fields', 'warning');
        return;
    }

    if (password.length < 6) {
        toast('Password must be at least 6 characters', 'warning');
        return;
    }

    const btn = document.querySelector('#page-signup .btn-primary');
    setButtonLoading(btn, true, 'Creating account…');

    try {
        const res  = await fetch(`${API}/api/auth/signup`, {
            method:  'POST',
            headers: { 'Content-Type': 'application/json' },
            body:    JSON.stringify({ username, email, password })
        });
        const data = await res.json();

        if (res.ok) {
            toast('Account created successfully! Please sign in.', 'success');
            document.getElementById('login-email').value = email;
            showPage('login');
        } else {
            toast(data.message || 'Could not create account', 'error');
        }
    } catch (err) {
        toast('Connection error. Is the server running?', 'error');
    } finally {
        setButtonLoading(btn, false, 'Create Account');
    }
}

/* ═══════════════════════════════════════════════════════
   AUTH — LOGOUT
════════════════════════════════════════════════════════ */
function logout() {
    const name    = currentUser;
    token         = null;
    currentUser   = '';
    currentNoteId = null;
    isDirty       = false;
    localStorage.removeItem('np_token');
    localStorage.removeItem('np_user');
    toast(`Goodbye, ${name}!`, 'info');
    showPage('login');
}

/* ═══════════════════════════════════════════════════════
   APP INIT
════════════════════════════════════════════════════════ */
function initApp() {
    document.getElementById('user-name').textContent   = currentUser;
    document.getElementById('user-avatar').textContent = currentUser.charAt(0).toUpperCase();
    loadNotes();
}

/* ═══════════════════════════════════════════════════════
   SIDEBAR
════════════════════════════════════════════════════════ */
function toggleSidebar() {
    document.getElementById('sidebar').classList.toggle('collapsed');
}

/* ═══════════════════════════════════════════════════════
   NOTES — LOAD ALL
════════════════════════════════════════════════════════ */
async function loadNotes() {
    try {
        const res = await fetch(`${API}/api/notes`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });

        if (res.status === 401) {
            toast('Session expired. Please sign in again.', 'warning');
            logout();
            return;
        }

        const data  = await res.json();
        const notes = data.notes || [];
        renderNoteList(notes);
    } catch (err) {
        toast('Could not load notes', 'error');
        console.error(err);
    }
}

/* ═══════════════════════════════════════════════════════
   NOTES — RENDER LIST
════════════════════════════════════════════════════════ */
function renderNoteList(notes) {
    const list  = document.getElementById('note-list');
    const empty = document.getElementById('notes-empty');
    const count = document.getElementById('notes-count');

    list.innerHTML    = '';
    count.textContent = notes.length;

    if (notes.length === 0) {
        empty.style.display = 'flex';
        return;
    }

    empty.style.display = 'none';

    notes.forEach((note, i) => {
        const li     = document.createElement('li');
        li.className = 'note-item' + (note.id === currentNoteId ? ' active' : '');

        const preview = (note.content || '').replace(/\s+/g, ' ').trim().slice(0, 60) || 'No content';
        const date    = formatDate(note.updatedAt);

        li.innerHTML = `
            <div class="note-item-title">${escapeHtml(note.title)}</div>
            <div class="note-item-preview">${escapeHtml(preview)}</div>
            <div class="note-item-date">${date}</div>
        `;

        li.onclick = () => openNote(note, li);
        list.appendChild(li);
    });
}

/* ═══════════════════════════════════════════════════════
   NOTES — OPEN
════════════════════════════════════════════════════════ */
function openNote(note, clickedEl) {
    currentNoteId = note.id;
    isDirty       = false;

    document.getElementById('editor-empty').style.display   = 'none';
    document.getElementById('editor-area').style.display    = 'flex';
    document.getElementById('editor-actions').style.display = 'flex';

    document.getElementById('note-title').value   = note.title;
    document.getElementById('note-content').value = note.content || '';
    document.getElementById('editor-meta').textContent =
        `Last edited ${formatDate(note.updatedAt)}  ·  Created ${formatDate(note.createdAt)}`;

    setSaveStatus('');

    document.querySelectorAll('.note-item').forEach(el => el.classList.remove('active'));
    if (clickedEl) clickedEl.classList.add('active');
}

/* ═══════════════════════════════════════════════════════
   NOTES — NEW
════════════════════════════════════════════════════════ */
function newNote() {
    currentNoteId = null;
    isDirty       = false;

    document.getElementById('editor-empty').style.display   = 'none';
    document.getElementById('editor-area').style.display    = 'flex';
    document.getElementById('editor-actions').style.display = 'flex';

    document.getElementById('note-title').value   = '';
    document.getElementById('note-content').value = '';
    document.getElementById('editor-meta').textContent = 'New note — unsaved';

    setSaveStatus('● unsaved', true);

    document.querySelectorAll('.note-item').forEach(el => el.classList.remove('active'));
    document.getElementById('note-title').focus();
}

/* ═══════════════════════════════════════════════════════
   NOTES — DIRTY STATE
════════════════════════════════════════════════════════ */
function markDirty() {
    if (!isDirty) {
        isDirty = true;
        setSaveStatus('● unsaved', true);
    }

    // Auto-save after 2 seconds of inactivity
    clearTimeout(saveTimeout);
    saveTimeout = setTimeout(() => {
        const title = document.getElementById('note-title').value.trim();
        if (title && isDirty) saveNote(true);
    }, 2000);
}

function setSaveStatus(text, dirty = false) {
    const el  = document.getElementById('save-status');
    el.textContent = text;
    el.className   = 'save-status' + (dirty ? ' dirty' : '');
}

/* ═══════════════════════════════════════════════════════
   NOTES — SAVE (goes through queue)
════════════════════════════════════════════════════════ */
async function saveNote(silent = false) {
    const title   = document.getElementById('note-title').value.trim();
    const content = document.getElementById('note-content').value;

    if (!title) {
        toast('Please enter a title for your note', 'warning');
        document.getElementById('note-title').focus();
        return;
    }

    if (!token) {
        toast('You are not logged in', 'error');
        return;
    }

    const isNew  = !currentNoteId;
    const method = isNew ? 'POST' : 'PUT';
    const url    = isNew
        ? `${API}/api/notes`
        : `${API}/api/notes/${currentNoteId}`;

    setSaveStatus('queuing…');

    try {
        const res  = await fetch(url, {
            method,
            headers: {
                'Content-Type':  'application/json',
                'Authorization': `Bearer ${token}`
            },
            body: JSON.stringify({ title, content })
        });

        const data = await res.json();

        if (res.status === 202) {
            // Successfully queued
            isDirty = false;
            setSaveStatus('⏳ queued');

            if (!silent) {
                toast(
                    isNew
                        ? `Note queued — position ${data.position} in queue`
                        : 'Note update queued',
                    'info'
                );
            }

            // Poll until the note appears in the sidebar
            pollForSave(title, 20);

        } else if (res.ok) {
            // Direct save fallback
            if (isNew && data.id) currentNoteId = data.id;
            isDirty = false;
            setSaveStatus('✓ saved');
            setTimeout(() => setSaveStatus(''), 2500);
            if (!silent) toast(isNew ? 'Note created' : 'Note saved', 'success');
            await loadNotes();

        } else {
            setSaveStatus('✗ failed', true);
            toast(data.message || 'Could not save note', 'error');
        }

    } catch (err) {
        setSaveStatus('✗ failed', true);
        toast('Connection error while saving', 'error');
        console.error(err);
    }
}

/* ═══════════════════════════════════════════════════════
   NOTES — POLL FOR SAVE CONFIRMATION
════════════════════════════════════════════════════════ */
function pollForSave(title, attemptsLeft) {
    if (attemptsLeft <= 0) {
        setSaveStatus('✗ timeout', true);
        toast('Save is taking longer than expected', 'warning');
        return;
    }

    setTimeout(async () => {
        await loadNotes();

        const found = Array.from(document.querySelectorAll('.note-item-title'))
            .some(el => el.textContent === title);

        if (found) {
            setSaveStatus('✓ saved');
            setTimeout(() => setSaveStatus(''), 2500);
            toast('Note saved successfully', 'success');

            document.querySelectorAll('.note-item').forEach(el => {
                const t = el.querySelector('.note-item-title');
                if (t && t.textContent === title) el.classList.add('active');
            });
        } else {
            pollForSave(title, attemptsLeft - 1);
        }
    }, 500);
}

/* ═══════════════════════════════════════════════════════
   NOTES — DELETE
════════════════════════════════════════════════════════ */
async function deleteNote() {
    if (!currentNoteId) {
        toast('No note is currently open', 'warning');
        return;
    }

    const title = document.getElementById('note-title').value.trim() || 'this note';

    const confirmed = await showConfirm(
        'Delete note?',
        `"${title}" will be permanently deleted and cannot be recovered.`
    );

    if (!confirmed) return;

    try {
        const res  = await fetch(`${API}/api/notes/${currentNoteId}`, {
            method:  'DELETE',
            headers: { 'Authorization': `Bearer ${token}` }
        });
        const data = await res.json();

        if (res.ok) {
            currentNoteId = null;
            isDirty       = false;
            document.getElementById('editor-area').style.display    = 'none';
            document.getElementById('editor-empty').style.display   = 'flex';
            document.getElementById('editor-actions').style.display = 'none';
            toast('Note deleted', 'info');
            await loadNotes();
        } else {
            toast(data.message || 'Could not delete note', 'error');
        }
    } catch (err) {
        toast('Connection error while deleting', 'error');
        console.error(err);
    }
}

/* ═══════════════════════════════════════════════════════
   QUEUE MONITORING
════════════════════════════════════════════════════════ */
async function checkQueueStatus() {
    try {
        const res  = await fetch(`${API}/api/queue/status`, {
            headers: { 'Authorization': `Bearer ${token}` }
        });

        if (!res.ok) return;

        const data      = await res.json();
        const indicator = document.getElementById('queue-indicator');
        const label     = document.getElementById('queue-label');

        if (data.queueSize > 0 || data.activeWorkers > 0) {
            indicator.style.display = 'flex';
            label.textContent = data.activeWorkers > 0
                ? `${data.activeWorkers} saving, ${data.queueSize} waiting`
                : `${data.queueSize} queued`;
        } else {
            indicator.style.display = 'none';
        }
    } catch (err) {
        // Silently ignore queue status errors
    }
}

/* ═══════════════════════════════════════════════════════
   CONFIRM MODAL
════════════════════════════════════════════════════════ */
function showConfirm(title, message) {
    return new Promise(resolve => {
        document.getElementById('modal-title').textContent   = title;
        document.getElementById('modal-message').textContent = message;
        document.getElementById('confirm-modal').style.display = 'flex';

        const ok     = document.getElementById('modal-confirm');
        const cancel = document.getElementById('modal-cancel');

        const cleanup = () => {
            document.getElementById('confirm-modal').style.display = 'none';
            ok.replaceWith(ok.cloneNode(true));
            cancel.replaceWith(cancel.cloneNode(true));
        };

        document.getElementById('modal-confirm').onclick = () => { cleanup(); resolve(true); };
        document.getElementById('modal-cancel').onclick  = () => { cleanup(); resolve(false); };
    });
}

/* ═══════════════════════════════════════════════════════
   HELPERS
════════════════════════════════════════════════════════ */
function togglePassword(inputId, btn) {
    const input = document.getElementById(inputId);
    if (input.type === 'password') {
        input.type    = 'text';
        btn.style.opacity = '1';
    } else {
        input.type    = 'password';
        btn.style.opacity = '0.5';
    }
}

function setButtonLoading(btn, loading, text) {
    if (!btn) return;
    btn.disabled = loading;
    btn.querySelector('span').textContent = text;
    btn.style.opacity = loading ? '0.7' : '1';
}

function formatDate(dateStr) {
    if (!dateStr) return '';
    const d    = new Date(dateStr);
    const now  = new Date();
    const diff = now - d;

    if (diff < 60000)     return 'just now';
    if (diff < 3600000)   return `${Math.floor(diff / 60000)}m ago`;
    if (diff < 86400000)  return `${Math.floor(diff / 3600000)}h ago`;
    if (diff < 604800000) return `${Math.floor(diff / 86400000)}d ago`;

    return d.toLocaleDateString('en-GB', {
        day: 'numeric', month: 'short', year: 'numeric'
    });
}

function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}
