# Как залить проект в GitHub (NIXXXON177/LivingVillagers)

Репозиторий уже создан: https://github.com/NIXXXON177/LivingVillagers
(он приватный — это ожидаемо и правильно, публикация мода не планируется,
см. ТЗ). Залить код должен ты сам со своего компьютера: у ассистента нет и
не должно быть доступа к твоему GitHub-аккаунту.

Ниже — два способа. **Способ А (через IntelliJ)** проще для новичка.

---

## Способ А. Через IntelliJ IDEA (рекомендуется)

1. Открой проект `livingvillagers` в IntelliJ (если ещё не открыт).
2. Сверху меню: `Git → Manage Remotes...` (если пункт называется
   `VCS → Git → Manage Remotes...` — то же самое, зависит от версии IDE).
   - Если репозиторий ещё не инициализирован как git — IntelliJ спросит,
     хочешь ли создать локальный git-репозиторий: жми "Create Repository"
     в папке проекта.
3. Добавь remote: имя `origin`, URL `https://github.com/NIXXXON177/LivingVillagers.git`.
4. Авторизуйся в GitHub прямо в IntelliJ, если ещё не делал этого:
   `File → Settings → Version Control → GitHub → Add Account` → войти через
   браузер (откроется окно логина GitHub, попросит разрешить доступ
   IntelliJ). Это безопаснее и проще, чем вручную возиться с токенами.
5. Добавь файлы в коммит: `Git → Commit...` (или `Ctrl+K`), выбери все
   файлы кроме тех, что попадают под `.gitignore` (build/, .idea/,
   .gradle/, run/ — их IntelliJ и не должен предлагать, они уже в
   `.gitignore`), напиши сообщение коммита, например:
   `Этап 0: каркас Fabric-мода (MC 1.21.1, Loom 1.12.7, Java 21)`
   → **Commit**.
6. `Git → Push...` (или `Ctrl+Shift+K`) → выбери ветку `main` → **Push**.

Если GitHub-репозиторий не совсем пустой (например, при создании ты
поставил галочку "Add README" или "Add .gitignore" на github.com) —
IntelliJ может предупредить о расхождении веток. В таком случае выбери
"Merge" при пуше/подтягивании (rebase тоже подойдёт, если знаешь, что это
такое; если не уверен — просто Merge).

---

## Способ Б. Через терминал (git CLI)

Открой терминал в папке проекта (в IntelliJ снизу есть вкладка Terminal,
либо PowerShell/Git Bash в Windows) и выполни по очереди:

```bash
git init                 # безвредно, если репозиторий уже инициализирован
git add -A
git commit -m "Этап 0: каркас Fabric-мода (MC 1.21.1, Loom 1.12.7, Java 21)"
git branch -M main
git remote add origin https://github.com/NIXXXON177/LivingVillagers.git
git push -u origin main
```

При первом `git push` Windows откроет окно авторизации GitHub в браузере
(если у тебя установлен Git Credential Manager, а он ставится вместе с
Git для Windows по умолчанию) — войди там под своим GitHub-аккаунтом,
дальше пароль/токен вводить не придётся.

Если войти через браузер не предлагается и Git просит логин/пароль —
**обычный пароль GitHub не примет** (они это отключили). Нужен Personal
Access Token:
`GitHub.com → Settings → Developer settings → Personal access tokens →
Generate new token (classic, scope "repo")` — скопированный токен вставь
вместо пароля при пуше.

Если получишь ошибку `rejected... fetch first` (значит на GitHub уже что-то
есть — например автосозданный README) — выполни:

```bash
git pull origin main --allow-unrelated-histories
git push -u origin main
```

---

## Что уже подготовлено в проекте для git

- `.gitignore` — уже настроен, исключает `build/`, `.gradle/`, `.idea/`,
  `run/` и т.п. (не нужно коммитить сгенерированные файлы и личные
  настройки IDE).
- `LICENSE.txt` — пометка "приватный проект, не публикуется".
- Локальный git-репозиторий с первым коммитом уже создан в этой рабочей
  копии (папка `.git/`) — если она долетит до тебя вместе со скачанной
  папкой проекта, шаги 1-5 из Способа А можно пропустить и сразу делать
  `Git → Push...`. Если `.git/` не долетела (это возможно, зависит от
  того, как именно ты скачиваешь проект) — просто пройди оба способа
  заново, ничего не сломается.
