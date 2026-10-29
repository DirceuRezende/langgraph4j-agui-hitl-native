import { SpringAiAgent } from '@ag-ui/spring-ai';
import { CopilotChat, CopilotKitProvider, useAgent, useInterrupt } from '@copilotkit/react-core/v2';
import { useMemo, useState } from 'react';

const THREAD_KEY = 'langgraph4j-copilotkit-thread';

function getInitialThreadId() {
  const saved = localStorage.getItem(THREAD_KEY);
  if (saved && /^[0-9a-f-]{36}$/i.test(saved)) return saved;
  const generated = crypto.randomUUID();
  localStorage.setItem(THREAD_KEY, generated);
  return generated;
}

function HumanReview() {
  useInterrupt({
    // Receives RUN_FINISHED with outcome.type === 'interrupt' from LangGraph4j.
    render: ({ interrupt, resolve }) => (
      <section className="hitl-card" aria-label="Aprovação humana">
        <div className="hitl-overline">✋ HUMAN IN THE LOOP</div>
        <h3>Decisão necessária</h3>
        <p>{interrupt?.message ?? 'Aprovar o rascunho?'}</p>
        <div className="hitl-actions">
          <button className="button-approve" onClick={() => void resolve({ approved: true })}>
            Aprovar e continuar
          </button>
          <button className="button-reject" onClick={() => void resolve({ approved: false })}>
            Rejeitar
          </button>
        </div>
      </section>
    ),
  });
  return null;
}

function ChatWorkspace({ threadId, onNewThread }: { threadId: string; onNewThread: () => void }) {
  // useAgent is CopilotKit's bridge to the same official AG-UI HttpAgent instance.
  // STATE_SNAPSHOT is applied by the SDK and reflected in this hook.
  const { agent, isReady } = useAgent({ agentId: 'default' });
  const graphState = agent.state as { finalMessage?: string; draft?: string };
  const finished = Boolean(graphState?.finalMessage);

  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="stack">JAVA 25 <span>·</span> SPRING BOOT 4 <span>·</span> COPILOTKIT V2</div>
        <div className="header-row">
          <div>
            <h1>Workflow com aprovação <em>↗</em></h1>
            <p>LangGraph4j + Spring AI + MongoDB · Adaptador AG-UI nativo</p>
          </div>
          <button className="new-thread" onClick={onNewThread}>+ Nova execução</button>
        </div>
        <nav className="process">
          <span>1 · Gerar rascunho</span><b>→</b><span>2 · HITL</span><b>→</b><span>3 · Executar ou rejeitar</span>
        </nav>
      </header>

      <main className="chat-panel">
        <div className="chat-meta">
          <div><span className="online-dot" /> <strong>CopilotChat</strong> · Agent <code>approval</code></div>
          <small>{isReady ? 'Conectado' : 'Preparando agente'} · {threadId.slice(0, 8)}…</small>
        </div>
        <HumanReview />
        <div className="chat-content">
          <CopilotChat
            agentId="default"
            className="copilot-chat"
            labels={{ chatInputPlaceholder: 'Descreva o rascunho que deseja gerar…' }}
          />
        </div>
        {finished && <div className="completed-banner">
          ✓ Workflow finalizado. Clique em <strong>Nova execução</strong> para iniciar outra tarefa.
        </div>}
      </main>
      <footer>Protótipo local · O backend exige uma aprovação explícita antes do nó de execução.</footer>
    </div>
  );
}

export default function App() {
  const [threadId, setThreadId] = useState(getInitialThreadId);
  // SpringAiAgent speaks the legacy Java AG-UI message format used by LangGraph4j 1.9.7.
  // Its transport is HttpAgent; it does not replace our Spring AI / LangGraph4j backend.
  const agent = useMemo(() => new SpringAiAgent({ url: '/sse/approval', threadId }), [threadId]);

  function resetThread() {
    const next = crypto.randomUUID();
    localStorage.setItem(THREAD_KEY, next);
    setThreadId(next);
  }

  return (
    // Official AG-UI Agent is registered in CopilotKit's local-development mode.
    // For production: use Copilot Runtime or secured/licensed selfManagedAgents.
    <CopilotKitProvider key={threadId} agentId="default"
      agents__unsafe_dev_only={{ default: agent }}>
      <ChatWorkspace threadId={threadId} onNewThread={resetThread} />
    </CopilotKitProvider>
  );
}
