import { useMemo, useState } from 'react';
import {
  Check,
  Copy,
  Download,
  FileCode2,
  FolderGit2,
  Layers,
  Search,
  ShieldAlert,
  ShieldCheck,
  SplitSquareVertical,
} from 'lucide-react';
import { buildAllFilesSplitBundle, OMNIDEV_REPO_FILES, RepoFile } from './repoManifest';
import { downloadRepoAsZip } from './zipExport';

type ViewSection = 'repository' | 'architecture' | 'sandbox';

const SECRET_RULES: { name: string; regex: RegExp }[] = [
  { name: 'GitHub Classic PAT', regex: /ghp_[A-Za-z0-9]{20,}/ },
  { name: 'GitHub Fine-Grained PAT', regex: /github_pat_[A-Za-z0-9_]{20,}/ },
  { name: 'Google / Gemini API Key', regex: /AIza[0-9A-Za-z\-_]{30,}/ },
  { name: 'HuggingFace Token', regex: /hf_[A-Za-z0-9]{20,}/ },
  { name: 'OpenAI / Anthropic Secret Key', regex: /sk-[A-Za-z0-9\-_]{20,}/ },
  { name: 'Groq API Key', regex: /gsk_[A-Za-z0-9]{20,}/ },
  { name: 'Telegram Bot Token', regex: /\b\d{8,10}:[A-Za-z0-9_-]{35}\b/ },
  { name: 'Private Key Header', regex: /-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----/ },
];

const DEFAULT_SANDBOX_SAMPLE = `### FILE: app/src/main/java/com/omniai/dev/SampleFeature.kt
\`\`\`kotlin
package com.omniai.dev

object SampleFeature {
    const val BASE_URL = "https://api.github.com"
    // Uncomment to test SecretLeakScanner:
    // val leakedToken = "ghp_1234567890abcdefghijklmnop"
}
\`\`\`

### FILE: app/src/main/res/values/strings_extra.xml
\`\`\`xml
<resources>
    <string name="sample_ready">OmniDev Ready</string>
</resources>
\`\`\``;

const BUILD_MATRIX = [
  { label: 'Application ID', value: 'com.omniai.dev' },
  { label: 'Gradle / AGP', value: '8.5 / 8.2.2' },
  { label: 'Kotlin / KSP', value: '1.9.22 / 1.9.22-1.0.17' },
  { label: 'Compose BOM / Compiler', value: '2024.02.02 / 1.5.8' },
  { label: 'SDK Targets', value: 'compileSdk 34 · targetSdk 34 · minSdk 26' },
  { label: 'Repository Footprint', value: `${OMNIDEV_REPO_FILES.length} source files` },
];

const ATOMIC_GIT_STEPS = [
  { step: '01', method: 'GET', endpoint: '/repos/{owner}/{repo}/git/ref/heads/{branch}', detail: 'Resolves head SHA or bootstraps README.md via Contents API on HTTP 404/409.' },
  { step: '02', method: 'GET', endpoint: '/repos/{owner}/{repo}/git/commits/{baseCommitSha}', detail: 'Extracts baseTreeSha for incremental tree composition.' },
  { step: '03', method: 'POST', endpoint: '/repos/{owner}/{repo}/git/blobs', detail: 'Uploads Base64 (NO_WRAP) blobs for each [NEW] or [MOD] file with live progress.' },
  { step: '04', method: 'POST', endpoint: '/repos/{owner}/{repo}/git/trees', detail: 'Builds atomic tree with mode 100644 and sha=null for deleted local files.' },
  { step: '05', method: 'POST', endpoint: '/repos/{owner}/{repo}/git/commits', detail: 'Creates a single commit referencing newTreeSha and baseCommitSha parent.' },
  { step: '06', method: 'PATCH', endpoint: '/repos/{owner}/{repo}/git/refs/heads/{branch}', detail: 'Advances branch ref once so 30 changed files trigger exactly 1 GitHub Actions run.' },
];

function parseSplitBlocks(raw: string): { path: string; lines: number; preview: string }[] {
  const regex = /^\s*#{2,4}\s*FILE\s*:\s*(.+?)\s*$/i;
  const blocks: { path: string; lines: number; preview: string }[] = [];
  let currentPath: string | null = null;
  let buf: string[] = [];

  const flush = () => {
    if (!currentPath) return;
    const trimmed = [...buf];
    while (trimmed.length > 0 && !trimmed[0].trim()) trimmed.shift();
    while (trimmed.length > 0 && !trimmed[trimmed.length - 1].trim()) trimmed.pop();
    if (trimmed.length >= 2 && trimmed[0].trim().startsWith('```') && trimmed[trimmed.length - 1].trim() === '```') {
      trimmed.shift();
      trimmed.pop();
    }
    blocks.push({
      path: currentPath,
      lines: trimmed.length,
      preview: trimmed.join('\n'),
    });
    buf = [];
  };

  for (const line of raw.replace(/\r\n/g, '\n').split('\n')) {
    const match = line.match(regex);
    if (match) {
      flush();
      const cleaned = match[1].trim().replace(/^['"`]|['"`]$/g, '').replace(/^\.?\//, '');
      currentPath = cleaned.includes('..') ? null : cleaned;
    } else if (currentPath) {
      buf.push(line);
    }
  }
  flush();
  return blocks;
}

export default function App() {
  const [section, setSection] = useState<ViewSection>('repository');
  const [selectedPath, setSelectedPath] = useState<string>(OMNIDEV_REPO_FILES[0].path);
  const [categoryFilter, setCategoryFilter] = useState<string>('All');
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [copiedId, setCopiedId] = useState<string | null>(null);
  const [sandboxInput, setSandboxInput] = useState<string>(DEFAULT_SANDBOX_SAMPLE);

  const categories = ['All', 'CI & Gradle', 'Resources', 'Data & Security', 'Core Engines', 'Compose UI'];

  const filteredFiles = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    return OMNIDEV_REPO_FILES.filter((f) => {
      const catOk = categoryFilter === 'All' || f.category === categoryFilter;
      const qOk = !q || f.path.toLowerCase().includes(q) || f.summary.toLowerCase().includes(q);
      return catOk && qOk;
    });
  }, [categoryFilter, searchQuery]);

  const activeFile: RepoFile = useMemo(
    () => OMNIDEV_REPO_FILES.find((f) => f.path === selectedPath) ?? OMNIDEV_REPO_FILES[0],
    [selectedPath]
  );

  const totalLines = useMemo(
    () => OMNIDEV_REPO_FILES.reduce((sum, f) => sum + f.content.split('\n').length, 0),
    []
  );

  const sandboxParsed = useMemo(() => parseSplitBlocks(sandboxInput), [sandboxInput]);
  const sandboxLeaks = useMemo(() => {
    const hits: { file: string; line: number; rule: string }[] = [];
    for (const block of sandboxParsed) {
      block.preview.split('\n').forEach((line, idx) => {
        for (const rule of SECRET_RULES) {
          if (rule.regex.test(line)) {
            hits.push({ file: block.path, line: idx + 1, rule: rule.name });
          }
        }
      });
    }
    return hits;
  }, [sandboxParsed]);

  const handleCopy = async (id: string, text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopiedId(id);
      window.setTimeout(() => setCopiedId((prev) => (prev === id ? null : prev)), 1800);
    } catch {
      // Clipboard blocked in restricted context
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#09090C] text-[#EDEDF5]">
      {/* Strict 3-Zone Top Bar */}
      <header className="h-14 border-b border-[#232330] bg-[#13131A] px-6 flex items-center justify-between gap-8 shrink-0">
        <a
          href="#repository"
          onClick={(e) => {
            e.preventDefault();
            setSection('repository');
          }}
          className="text-base font-bold tracking-tight text-[#EDEDF5] whitespace-nowrap shrink-0"
        >
          OmniDev
        </a>

        <nav className="flex items-center gap-6 text-sm font-medium">
          <button
            type="button"
            onClick={() => setSection('repository')}
            className={`whitespace-nowrap shrink-0 transition-colors cursor-pointer ${
              section === 'repository' ? 'text-[#00E5FF]' : 'text-[#9898AE] hover:text-[#EDEDF5]'
            }`}
          >
            Repository
          </button>
          <button
            type="button"
            onClick={() => setSection('architecture')}
            className={`whitespace-nowrap shrink-0 transition-colors cursor-pointer ${
              section === 'architecture' ? 'text-[#00E5FF]' : 'text-[#9898AE] hover:text-[#EDEDF5]'
            }`}
          >
            Build Matrix
          </button>
          <button
            type="button"
            onClick={() => setSection('sandbox')}
            className={`whitespace-nowrap shrink-0 transition-colors cursor-pointer ${
              section === 'sandbox' ? 'text-[#00E5FF]' : 'text-[#9898AE] hover:text-[#EDEDF5]'
            }`}
          >
            Splitter Lab
          </button>
        </nav>

        <button
          type="button"
          onClick={() => downloadRepoAsZip(OMNIDEV_REPO_FILES)}
          className="inline-flex items-center gap-2 px-4 py-2 text-xs font-semibold rounded-lg bg-[#00E5FF] text-[#09090C] hover:bg-[#33ECFF] transition-colors whitespace-nowrap shrink-0 cursor-pointer"
        >
          <Download className="w-3.5 h-3.5" />
          Download ZIP
        </button>
      </header>

      {/* Repository Explorer Workspace */}
      {section === 'repository' && (
        <div className="flex-1 grid grid-cols-1 lg:grid-cols-[310px_1fr] min-h-0">
          {/* Left Sidebar: File Tree & Filter */}
          <aside className="border-r border-[#232330] bg-[#0D0D12] flex flex-col min-h-0">
            <div className="p-4 border-b border-[#232330] space-y-3">
              <div className="flex items-center justify-between text-xs text-[#9898AE] tabular-nums">
                <span>com.omniai.dev</span>
                <span>{OMNIDEV_REPO_FILES.length} files · {totalLines.toLocaleString()} lines</span>
              </div>

              <div className="relative">
                <Search className="w-3.5 h-3.5 text-[#9898AE] absolute left-3 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Filter repository files..."
                  className="w-full bg-[#13131A] border border-[#232330] rounded-lg pl-8 pr-3 py-1.5 text-xs text-[#EDEDF5] placeholder:text-[#6E6E85] focus:outline-none focus:border-[#00E5FF]"
                />
              </div>

              <div className="flex flex-wrap gap-1">
                {categories.map((cat) => (
                  <button
                    key={cat}
                    type="button"
                    onClick={() => setCategoryFilter(cat)}
                    className={`px-2 py-1 text-[11px] font-medium rounded-md transition-colors whitespace-nowrap cursor-pointer ${
                      categoryFilter === cat
                        ? 'bg-[#1D2733] text-[#00E5FF]'
                        : 'text-[#9898AE] hover:text-[#EDEDF5] hover:bg-[#13131A]'
                    }`}
                  >
                    {cat}
                  </button>
                ))}
              </div>
            </div>

            <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
              {filteredFiles.map((file) => {
                const isSelected = file.path === activeFile.path;
                const fileName = file.path.split('/').pop() ?? file.path;
                const dirPath = file.path.includes('/') ? file.path.slice(0, file.path.lastIndexOf('/')) : '.';
                return (
                  <button
                    key={file.path}
                    type="button"
                    onClick={() => setSelectedPath(file.path)}
                    className={`w-full text-left px-3 py-2 rounded-lg transition-colors flex items-start gap-2.5 cursor-pointer ${
                      isSelected
                        ? 'bg-[#13131A] text-[#00E5FF] border border-[#232330]'
                        : 'text-[#C8C8D8] hover:bg-[#13131A]/60 border border-transparent'
                    }`}
                  >
                    <FileCode2 className={`w-4 h-4 mt-0.5 shrink-0 ${isSelected ? 'text-[#00E5FF]' : 'text-[#6E6E85]'}`} />
                    <div className="min-w-0 flex-1">
                      <div className="font-mono text-xs font-medium truncate">{fileName}</div>
                      <div className="font-mono text-[10px] text-[#7A7A92] truncate">{dirPath}</div>
                    </div>
                  </button>
                );
              })}
            </div>

            <div className="p-3 border-t border-[#232330]">
              <button
                type="button"
                onClick={() => handleCopy('all-bundle', buildAllFilesSplitBundle())}
                className="w-full py-2 px-3 rounded-lg border border-[#232330] bg-[#13131A] hover:border-[#BB86FC] text-xs font-medium text-[#EDEDF5] flex items-center justify-center gap-2 transition-colors cursor-pointer"
              >
                {copiedId === 'all-bundle' ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-[#22C55E]" />
                    Copied All {OMNIDEV_REPO_FILES.length} Files (### FILE: Bundle)
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-[#BB86FC]" />
                    Copy All as ### FILE: Bundle
                  </>
                )}
              </button>
            </div>
          </aside>

          {/* Right Main Pane: Source Viewer */}
          <main className="flex flex-col min-w-0 min-h-0 bg-[#09090C]">
            <div className="px-6 py-4 border-b border-[#232330] bg-[#13131A] flex flex-wrap items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="font-mono text-sm font-semibold text-[#EDEDF5] truncate">{activeFile.path}</div>
                <div className="text-xs text-[#9898AE] mt-0.5 flex items-center gap-2">
                  <span>{activeFile.category}</span>
                  <span aria-hidden="true">·</span>
                  <span className="tabular-nums">{activeFile.content.split('\n').length} lines</span>
                  <span aria-hidden="true">·</span>
                  <span className="truncate">{activeFile.summary}</span>
                </div>
              </div>

              <button
                type="button"
                onClick={() => handleCopy(activeFile.path, activeFile.content)}
                className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-[#232330] bg-[#09090C] hover:border-[#00E5FF] text-xs font-medium text-[#EDEDF5] transition-colors whitespace-nowrap shrink-0 cursor-pointer"
              >
                {copiedId === activeFile.path ? (
                  <>
                    <Check className="w-3.5 h-3.5 text-[#22C55E]" />
                    Copied
                  </>
                ) : (
                  <>
                    <Copy className="w-3.5 h-3.5 text-[#00E5FF]" />
                    Copy File
                  </>
                )}
              </button>
            </div>

            <div className="flex-1 overflow-auto p-6">
              <pre className="text-xs leading-6 font-mono text-[#D9D9E8] tabular-nums">
                {activeFile.content.split('\n').map((line, i) => (
                  <div key={i} className="flex hover:bg-[#13131A] px-2 rounded">
                    <span className="w-11 shrink-0 select-none text-right pr-4 text-[#5A5A72]">{i + 1}</span>
                    <span className="whitespace-pre">{line}</span>
                  </div>
                ))}
              </pre>
            </div>
          </main>
        </div>
      )}

      {/* Architecture & Locked Build Matrix */}
      {section === 'architecture' && (
        <main className="max-w-5xl w-full mx-auto px-6 py-8 space-y-8">
          <div className="space-y-2">
            <h1 className="text-2xl font-bold tracking-tight text-[#EDEDF5]">
              OmniDev Native Android Architecture
            </h1>
            <p className="text-sm text-[#9898AE] max-w-2xl">
              App 1 of the Omni Suite (<span className="font-mono text-[#00E5FF]">com.omniai.dev</span>). Engineered with a locked Gradle 8.5 / AGP 8.2.2 / Kotlin 1.9.22 / Compose BOM 2024.02.02 build matrix and pure XML adaptive resources for zero-error CI compilation.
            </p>
          </div>

          <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {BUILD_MATRIX.map((item) => (
              <div key={item.label} className="p-4 rounded-xl bg-[#13131A] border border-[#232330]">
                <div className="text-xs text-[#9898AE]">{item.label}</div>
                <div className="mt-1 font-mono text-sm font-semibold text-[#EDEDF5] tabular-nums">{item.value}</div>
              </div>
            ))}
          </section>

          <section className="rounded-xl bg-[#13131A] border border-[#232330] overflow-hidden">
            <div className="px-5 py-4 border-b border-[#232330] flex items-center gap-2">
              <FolderGit2 className="w-4 h-4 text-[#00E5FF]" />
              <h2 className="text-sm font-semibold text-[#EDEDF5]">6-Step Atomic Git Data REST Engine (Pure OkHttp3)</h2>
            </div>
            <div className="divide-y divide-[#232330]">
              {ATOMIC_GIT_STEPS.map((row) => (
                <div key={row.step} className="px-5 py-3.5 grid grid-cols-1 md:grid-cols-[40px_72px_1fr_1.1fr] gap-2 items-baseline text-xs">
                  <span className="font-mono text-[#7A7A92] tabular-nums">{row.step}</span>
                  <span className="font-mono font-semibold text-[#BB86FC]">{row.method}</span>
                  <span className="font-mono text-[#00E5FF] break-all">{row.endpoint}</span>
                  <span className="text-[#9898AE]">{row.detail}</span>
                </div>
              ))}
            </div>
          </section>

          <section className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div className="p-5 rounded-xl bg-[#13131A] border border-[#232330] space-y-2">
              <div className="flex items-center gap-2 text-sm font-semibold text-[#EDEDF5]">
                <Layers className="w-4 h-4 text-[#00E5FF]" />
                4-Tab AMOLED Compose Workspace
              </div>
              <ul className="text-xs text-[#9898AE] space-y-1.5 leading-relaxed">
                <li>1. Projects &amp; Kanban: Room DB, pending diff counts, private repo creation, Actions watcher.</li>
                <li>2. Editor &amp; Splitter: Local SHA-256 diff explorer, monospace editor, and <span className="font-mono text-[#EDEDF5]">### FILE:</span> parser + Android <span className="font-mono text-[#EDEDF5]">ACTION_SEND</span> receiver.</li>
                <li>3. Hybrid Terminal &amp; LAN Studio: <span className="font-mono text-[#EDEDF5]">/system/bin/sh</span>, OmniCLI, Termux Bridge, and <span className="font-mono text-[#EDEDF5]">ServerSocket :8787</span> dashboard with 6-digit PIN.</li>
                <li>4. Vault, Backup &amp; Build-Doctor: EncryptedSharedPreferences, Secret Guardian, AI Diff Fixer, OTA APK installer, and AES-256-GCM <span className="font-mono text-[#EDEDF5]">.omniai.enc</span> export.</li>
              </ul>
            </div>

            <div className="p-5 rounded-xl bg-[#13131A] border border-[#232330] space-y-2">
              <div className="flex items-center gap-2 text-sm font-semibold text-[#EDEDF5]">
                <ShieldCheck className="w-4 h-4 text-[#22C55E]" />
                Zero-Error AAPT2 &amp; Security Locks
              </div>
              <ul className="text-xs text-[#9898AE] space-y-1.5 leading-relaxed">
                <li>Pure XML adaptive icon (<span className="font-mono text-[#EDEDF5]">ic_bg_dev.xml</span>, <span className="font-mono text-[#EDEDF5]">ic_fg_dev.xml</span>, <span className="font-mono text-[#EDEDF5]">ic_launcher_dev.xml</span>) and 24dp monochrome <span className="font-mono text-[#EDEDF5]">ic_stat_omni.xml</span>.</li>
                <li>No <span className="font-mono text-[#EDEDF5]">gradle/wrapper/</span> files; GitHub Actions invokes <span className="font-mono text-[#EDEDF5]">gradle assembleDebug</span> or <span className="font-mono text-[#EDEDF5]">assembleRelease</span> via <span className="font-mono text-[#EDEDF5]">setup-gradle@v3</span>.</li>
                <li><span className="font-mono text-[#EDEDF5]">android:allowBackup=&quot;false&quot;</span> with explicit <span className="font-mono text-[#EDEDF5]">FOREGROUND_SERVICE_DATA_SYNC</span> and Termux <span className="font-mono text-[#EDEDF5]">&lt;queries&gt;</span>.</li>
                <li>Pre-Push Secret Guardian blocks commits containing GitHub, Gemini, OpenAI, HF, Groq, Telegram, or Private Key patterns before any blob leaves the device.</li>
              </ul>
            </div>
          </section>
        </main>
      )}

      {/* Interactive Splitter & Secret Guardian Sandbox */}
      {section === 'sandbox' && (
        <main className="max-w-6xl w-full mx-auto px-6 py-8 space-y-6">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h1 className="text-xl font-bold text-[#EDEDF5] flex items-center gap-2">
                <SplitSquareVertical className="w-5 h-5 text-[#BB86FC]" />
                Live ### FILE: Splitter &amp; Secret Guardian Lab
              </h1>
              <p className="text-xs text-[#9898AE] mt-1">
                Test the exact parsing and pre-push leak detection logic implemented in <span className="font-mono text-[#00E5FF]">AiMultiFileSplitter.kt</span> and <span className="font-mono text-[#00E5FF]">SecretLeakScanner.kt</span>.
              </p>
            </div>
            <button
              type="button"
              onClick={() => setSandboxInput(DEFAULT_SANDBOX_SAMPLE)}
              className="px-3 py-1.5 text-xs font-medium rounded-lg border border-[#232330] bg-[#13131A] hover:border-[#00E5FF] text-[#EDEDF5] cursor-pointer"
            >
              Reset Sample
            </button>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <div className="flex flex-col gap-2">
              <label htmlFor="splitter-input" className="text-xs font-medium text-[#9898AE]">
                Paste AI Multi-File Output
              </label>
              <textarea
                id="splitter-input"
                value={sandboxInput}
                onChange={(e) => setSandboxInput(e.target.value)}
                rows={18}
                spellCheck={false}
                className="w-full rounded-xl bg-[#13131A] border border-[#232330] p-4 font-mono text-xs text-[#EDEDF5] leading-5 focus:outline-none focus:border-[#00E5FF]"
              />
            </div>

            <div className="space-y-4">
              <div className="rounded-xl bg-[#13131A] border border-[#232330] p-4 space-y-3">
                <div className="flex items-center justify-between text-xs">
                  <span className="font-semibold text-[#EDEDF5]">Pre-Push Secret Guardian</span>
                  <span className={sandboxLeaks.length > 0 ? 'text-[#EF4444] font-semibold' : 'text-[#22C55E] font-semibold'}>
                    {sandboxLeaks.length > 0 ? `Blocked · ${sandboxLeaks.length} leak(s)` : 'Clear · 0 leaks'}
                  </span>
                </div>
                {sandboxLeaks.length === 0 ? (
                  <p className="text-xs text-[#9898AE]">
                    No credentials detected. Try uncommenting the sample <span className="font-mono text-[#EDEDF5]">ghp_</span> line on the left to verify leak blocking.
                  </p>
                ) : (
                  <div className="space-y-2">
                    {sandboxLeaks.map((hit, idx) => (
                      <div key={idx} className="p-3 rounded-lg bg-[#09090C] border border-[#EF4444]/40 flex items-start gap-2.5 text-xs">
                        <ShieldAlert className="w-4 h-4 text-[#EF4444] shrink-0 mt-0.5" />
                        <div>
                          <div className="font-semibold text-[#EF4444]">{hit.rule}</div>
                          <div className="font-mono text-[#00E5FF] mt-0.5">
                            {hit.file} : line {hit.line}
                          </div>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div className="rounded-xl bg-[#13131A] border border-[#232330] p-4 space-y-3">
                <div className="flex items-center justify-between text-xs">
                  <span className="font-semibold text-[#EDEDF5]">Parsed ### FILE: Targets</span>
                  <span className="text-[#9898AE] tabular-nums">{sandboxParsed.length} file(s)</span>
                </div>
                {sandboxParsed.length === 0 ? (
                  <p className="text-xs text-[#9898AE]">No ### FILE: blocks detected yet.</p>
                ) : (
                  <div className="space-y-2">
                    {sandboxParsed.map((block) => (
                      <div key={block.path} className="p-3 rounded-lg bg-[#09090C] border border-[#232330]">
                        <div className="flex items-center justify-between gap-2 text-xs">
                          <span className="font-mono text-[#00E5FF] truncate">{block.path}</span>
                          <span className="font-mono text-[#7A7A92] tabular-nums shrink-0">{block.lines} lines</span>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </main>
      )}
    </div>
  );
}
