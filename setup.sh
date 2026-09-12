#!/usr/bin/env bash
#
# setup.sh — verifica as dependências e sobe o Flash Booking inteiro:
#            infraestrutura (PostgreSQL + Kafka + Kafka UI), backend e frontend.
#
# Uso:
#   ./setup.sh                       verifica tudo e sobe tudo (Ctrl+C encerra tudo)
#   ./setup.sh --check               só verifica as dependências e sai
#   ./setup.sh --backend-only        infraestrutura + backend, sem frontend
#   ./setup.sh --stop                encerra backend, frontend e containers
#   ./setup.sh --fresh               apaga os volumes do banco antes de subir (começa do zero)
#   ./setup.sh --fast-expiration     TTL de reserva de 25s, para demonstrar a expiração ao vivo
#   ./setup.sh --logs                acompanha os logs de backend e frontend
#   ./setup.sh --status              mostra o que está no ar
#
# A parte de frontend é delegada a ../flash-booking-front-end/setup.sh, para que a
# lógica de instalação e subida do frontend exista em um só lugar.

set -euo pipefail

# --------------------------------------------------------------------------------------
# Caminhos e parâmetros
# --------------------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND_DIR="${FRONTEND_DIR:-$ROOT_DIR/../flash-booking-front-end}"
# Normaliza o caminho: sem isto, todas as mensagens exibiriam ".../flash-booking/../front".
if [ -d "$FRONTEND_DIR" ]; then
    FRONTEND_DIR="$(cd "$FRONTEND_DIR" && pwd)"
fi

RUN_DIR="$ROOT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
# O `bootRun` executa a aplicação em uma JVM que é filha do DAEMON do Gradle, e não do
# cliente `gradlew` — então encerrar a árvore do gradlew não a alcança. O PID real é
# descoberto pela porta que ela escuta e guardado aqui, para que a parada seja precisa.
BACKEND_APP_PID_FILE="$RUN_DIR/backend-app.pid"
BACKEND_LOG="$RUN_DIR/backend.log"

# Portas: os mesmos valores que o docker-compose.yml e o application.yml usam por padrão.
BACKEND_PORT="${SERVER_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"
POSTGRES_PORT="${POSTGRES_PORT:-5432}"
KAFKA_PORT="${KAFKA_PORT:-9092}"
KAFKA_UI_PORT="${KAFKA_UI_PORT:-8081}"

HEALTH_URL="http://localhost:$BACKEND_PORT/actuator/health"
FRONTEND_URL="http://localhost:$FRONTEND_PORT"
KAFKA_UI_URL="http://localhost:$KAFKA_UI_PORT"

# A classe principal identifica o processo da aplicação — é o que permite encerrá-la
# mesmo quando o processo do Gradle que a lançou já não existe.
APP_MAIN_CLASS="com.example.flashbooking.FlashBookingApplication"

# Java mínimo para EXECUTAR o Gradle 9.x. O build em si compila com a toolchain 21,
# que o Gradle provisiona sozinho (plugin foojay, em settings.gradle.kts).
MIN_JAVA_MAJOR=17
BUILD_TOOLCHAIN_JAVA=21

# A primeira subida baixa dependências do Gradle e, se preciso, o JDK 21 — por isso o
# teto é generoso. As seguintes sobem em poucos segundos.
BACKEND_BOOT_TIMEOUT="${BACKEND_BOOT_TIMEOUT:-600}"
INFRA_HEALTH_TIMEOUT="${INFRA_HEALTH_TIMEOUT:-180}"

# --------------------------------------------------------------------------------------
# Saída
# --------------------------------------------------------------------------------------

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
    C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
    C_RESET=''; C_BOLD=''; C_DIM=''; C_RED=''; C_GREEN=''; C_YELLOW=''; C_BLUE=''
fi

step()  { printf '\n%s▸ %s%s\n' "$C_BOLD$C_BLUE" "$*" "$C_RESET"; }
ok()    { printf '  %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '  %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*"; }
err()   { printf '  %s✗%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
info()  { printf '  %s%s%s\n' "$C_DIM" "$*" "$C_RESET"; }

FAILURES=0
record_failure() { FAILURES=$((FAILURES + 1)); }

# --------------------------------------------------------------------------------------
# Utilidades
# --------------------------------------------------------------------------------------

COMPOSE=""

detect_compose() {
    if docker compose version >/dev/null 2>&1; then
        COMPOSE="docker compose"
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE="docker-compose"
    fi
}

# $COMPOSE precisa sofrer word splitting ("docker compose" são dois argumentos).
compose() { $COMPOSE "$@"; }

port_pid() {
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -1
    fi
}

port_in_use() {
    if command -v lsof >/dev/null 2>&1; then
        [ -n "$(port_pid "$1")" ]
    elif command -v nc >/dev/null 2>&1; then
        nc -z localhost "$1" >/dev/null 2>&1
    else
        (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
    fi
}

http_ok() { curl -fsS -m 3 -o /dev/null "$1" >/dev/null 2>&1; }

backend_up() {
    curl -fsS -m 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"'
}

kill_tree() {
    local pid="$1" kid
    for kid in $(pgrep -P "$pid" 2>/dev/null || true); do
        kill_tree "$kid"
    done
    kill "$pid" 2>/dev/null || true
}

major_version() { printf '%s' "$1" | sed -E 's/^v?([0-9]+).*/\1/'; }

# Major do Java no PATH, ou vazio quando não há runtime de verdade.
#
# Só perguntar ao `command -v java` não serve: no macOS o /usr/bin/java existe mesmo sem
# JDK nenhum instalado — é um stub que responde "Unable to locate a Java Runtime". E a
# saída de um JDK real pode trazer ruído antes da versão (o "Picked up JAVA_TOOL_OPTIONS"
# é o caso clássico). Por isso a versão é procurada, e não assumida na primeira linha;
# não achando, a função devolve vazio e quem chama trata como "Java não encontrado" — em
# vez de comparar o texto do erro como se fosse número.
java_major() {
    local raw major
    raw="$(java -version 2>&1 | grep -m1 'version "' || true)"
    [ -n "$raw" ] || return 0

    raw="$(printf '%s' "$raw" | sed -E 's/.*version "([0-9._]+).*/\1/')"
    major="$(major_version "$raw")"
    # Java 8 e anteriores reportam "1.8.0_xxx": o major real é o segundo componente.
    if [ "$major" = "1" ]; then
        major="$(printf '%s' "$raw" | cut -d. -f2)"
    fi

    # Salvaguarda final: devolve número, ou nada.
    case "$major" in
        ''|*[!0-9]*) return 0 ;;
    esac
    printf '%s' "$major"
}

# Sobe serviços do compose, com uma retentativa.
#
# A primeira tentativa pode falhar legitimamente quando um container do ciclo anterior
# ainda está terminando o shutdown: o compose lê o estado "exited" do container e desiste
# com "dependency failed to start". Esperar e repetir resolve — e é melhor do que
# devolver ao usuário um erro cru do compose e pedir que ele rode o comando de novo.
compose_up() {
    if compose up -d "$@" >/dev/null 2>&1; then
        return 0
    fi

    warn "O compose não conseguiu subir ($*) na primeira tentativa; aguardando 8s e repetindo."
    sleep 8
    if compose up -d "$@" >/dev/null 2>&1; then
        return 0
    fi

    err "Não foi possível subir: $*"
    info "Veja o motivo com: $COMPOSE logs $*"
    info "Se o estado dos containers estiver inconsistente: ./setup.sh --fresh"
    return 1
}

# Id do container de um serviço do compose, quando ele existe.
service_container() { compose ps -q "$1" 2>/dev/null | head -1; }

service_running() {
    local cid
    cid="$(service_container "$1")"
    [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = "true" ]
}

# --------------------------------------------------------------------------------------
# 1. Verificação de dependências
# --------------------------------------------------------------------------------------

check_dependencies() {
    local want_frontend="$1"

    # O frontend vive em um repositório separado, que precisa estar clonado ao lado deste.
    # A existência dele é conferida ANTES de tudo, e não só na hora de delegar: o resumo de
    # falhas abaixo interrompe a função, então descobrir a ausência do repositório apenas
    # depois que todo o resto passou obrigaria a rodar o script duas vezes para enxergar os
    # dois problemas.
    local frontend_ready="no"
    if [ "$want_frontend" = "yes" ]; then
        step "Verificando o repositório do frontend"
        if [ -f "$FRONTEND_DIR/setup.sh" ]; then
            ok "Frontend encontrado em $FRONTEND_DIR"
            if [ ! -x "$FRONTEND_DIR/setup.sh" ]; then
                chmod +x "$FRONTEND_DIR/setup.sh"
                info "setup.sh do frontend não estava executável — corrigido com chmod +x."
            fi
            frontend_ready="yes"
        elif [ -d "$FRONTEND_DIR" ]; then
            err "O diretório $FRONTEND_DIR existe, mas não contém um setup.sh."
            info "Confirme que ele é mesmo o repositório do frontend, e não uma pasta homônima."
            info "Ou aponte o caminho certo: FRONTEND_DIR=/caminho/do/front ./setup.sh"
            info "Ou suba só o backend: ./setup.sh --backend-only"
            record_failure
        else
            err "Repositório do frontend não encontrado em $FRONTEND_DIR."
            info "Ele é um repositório separado; clone-o como irmão deste:"
            info "  git clone <url-do-flash-booking-front-end> $FRONTEND_DIR"
            info "Ou aponte o caminho certo: FRONTEND_DIR=/caminho/do/front ./setup.sh"
            info "Ou suba só o backend: ./setup.sh --backend-only"
            record_failure
        fi
    fi

    step "Verificando dependências do backend"

    # --- Docker CLI ---
    if command -v docker >/dev/null 2>&1; then
        ok "Docker CLI: $(docker --version | sed 's/,.*//')"
    else
        err "Docker não encontrado — é obrigatório (PostgreSQL e Kafka rodam em container)."
        info "Instale o Docker Desktop: https://www.docker.com/products/docker-desktop"
        record_failure
    fi

    # --- daemon do Docker realmente no ar ---
    if command -v docker >/dev/null 2>&1; then
        if docker info >/dev/null 2>&1; then
            ok "Daemon do Docker respondendo"
        else
            err "O Docker está instalado, mas o daemon não está respondendo."
            info "Abra o Docker Desktop e espere ele terminar de subir."
            record_failure
        fi
    fi

    # --- Docker Compose v2 ---
    detect_compose
    if [ -n "$COMPOSE" ]; then
        ok "Docker Compose: $(compose version 2>/dev/null | head -1)"
    else
        err "Docker Compose não encontrado (nem 'docker compose', nem 'docker-compose')."
        info "O Docker Desktop já inclui o Compose v2."
        record_failure
    fi

    # --- Java: só para rodar o Gradle; a compilação usa a toolchain 21 ---
    local jmajor=""
    if command -v java >/dev/null 2>&1; then
        jmajor="$(java_major)"
    fi

    if [ -z "$jmajor" ]; then
        err "Java não encontrado — necessário para executar o Gradle."
        if command -v java >/dev/null 2>&1; then
            info "O comando 'java' existe, mas não há runtime por trás dele (no macOS, /usr/bin/java"
            info "é apenas um stub quando nenhum JDK está instalado)."
        fi
        info "Instale com: brew install openjdk@21"
        info "Depois confira com: java -version"
        record_failure
    elif [ "$jmajor" -lt "$MIN_JAVA_MAJOR" ]; then
        err "Java $jmajor é antigo demais — o Gradle 9 exige Java $MIN_JAVA_MAJOR ou superior."
        info "Instale com: brew install openjdk@21"
        record_failure
    else
        ok "Java $jmajor encontrado (mínimo para o Gradle: $MIN_JAVA_MAJOR)"
        if [ "$jmajor" != "$BUILD_TOOLCHAIN_JAVA" ]; then
            info "O build compila com a toolchain Java $BUILD_TOOLCHAIN_JAVA, que o Gradle provisiona sozinho."
            if [ -d "$HOME/.gradle/jdks" ] && ls "$HOME/.gradle/jdks" 2>/dev/null | grep -q "$BUILD_TOOLCHAIN_JAVA"; then
                info "JDK $BUILD_TOOLCHAIN_JAVA já está no cache do Gradle — sem download na subida."
            else
                warn "O JDK $BUILD_TOOLCHAIN_JAVA será baixado na primeira subida (alguns minutos, uma única vez)."
            fi
        fi
    fi

    # --- Gradle wrapper ---
    if [ -x "$ROOT_DIR/gradlew" ]; then
        ok "Gradle wrapper presente e executável"
    elif [ -f "$ROOT_DIR/gradlew" ]; then
        warn "gradlew existe mas não está executável — corrigindo com chmod +x."
        chmod +x "$ROOT_DIR/gradlew"
        ok "Gradle wrapper agora é executável"
    else
        err "gradlew não encontrado em $ROOT_DIR"
        record_failure
    fi

    # --- curl: este script depende dele para saber quando a aplicação está pronta ---
    if command -v curl >/dev/null 2>&1; then
        ok "curl disponível"
    else
        err "curl não encontrado — é como este script detecta que o backend ficou pronto."
        record_failure
    fi

    # --- docker-compose.yml ---
    if [ -f "$ROOT_DIR/docker-compose.yml" ]; then
        ok "docker-compose.yml encontrado"
    else
        err "docker-compose.yml não encontrado em $ROOT_DIR"
        record_failure
    fi

    # --- portas ---
    check_port_free "$POSTGRES_PORT" "PostgreSQL" postgres
    check_port_free "$KAFKA_PORT"    "Kafka"      kafka
    check_port_free "$KAFKA_UI_PORT" "Kafka UI"   kafka-ui

    if port_in_use "$BACKEND_PORT"; then
        if backend_up; then
            warn "Porta $BACKEND_PORT ocupada, mas é o próprio backend e ele está saudável — será reaproveitado."
        else
            err "Porta $BACKEND_PORT está ocupada por outro processo (PID $(port_pid "$BACKEND_PORT"))."
            info "Libere a porta, ou rode com outra: SERVER_PORT=8090 ./setup.sh"
            record_failure
        fi
    else
        ok "Porta $BACKEND_PORT livre (backend)"
    fi

    if [ "$FAILURES" -gt 0 ]; then
        printf '\n%s✗ %d dependência(s) faltando ou inválida(s).%s\n\n' \
            "$C_RED$C_BOLD" "$FAILURES" "$C_RESET"
        return 1
    fi

    ok "Dependências do backend satisfeitas"

    # --- frontend: a existência do repositório já foi conferida acima; o resto das
    #     dependências dele (Node, npm, porta) é sabido pelo script do próprio frontend ---
    if [ "$frontend_ready" = "yes" ]; then
        printf '\n'
        if ! "$FRONTEND_DIR/setup.sh" --check; then
            record_failure
            return 1
        fi
    fi

    return 0
}

# Uma porta de infraestrutura só é problema se estiver ocupada por algo que NÃO seja
# o container correspondente deste projeto — nesse caso, ele é reaproveitado.
#
# A função sempre devolve 0: ela registra a falha em FAILURES e deixa a decisão para o
# resumo de check_dependencies. Devolver 1 aqui faria o `set -e` abortar a verificação
# na primeira porta ocupada, escondendo as checagens seguintes de quem só quer saber
# tudo o que falta ajustar.
check_port_free() {
    local port="$1" label="$2" service="$3"

    if ! port_in_use "$port"; then
        ok "Porta $port livre ($label)"
        return 0
    fi

    if [ -n "$COMPOSE" ] && (cd "$ROOT_DIR" && service_running "$service"); then
        warn "Porta $port ocupada pelo container '$service' deste projeto — será reaproveitado."
        return 0
    fi

    err "Porta $port está ocupada por outro processo (PID $(port_pid "$port")) — necessária para o $label."
    case "$service" in
        postgres) info "Se for um PostgreSQL local, pare-o: brew services stop postgresql" ;;
        kafka)    info "Pare o Kafka que estiver rodando, ou mude a porta: KAFKA_PORT=9192 ./setup.sh" ;;
        kafka-ui) info "Mude a porta: KAFKA_UI_PORT=8181 ./setup.sh" ;;
    esac
    record_failure
    return 0
}

# --------------------------------------------------------------------------------------
# 2. Infraestrutura
# --------------------------------------------------------------------------------------

start_infrastructure() {
    step "Subindo a infraestrutura (PostgreSQL, Kafka, Kafka UI)"
    cd "$ROOT_DIR"

    if [ "$FRESH" = "yes" ]; then
        warn "--fresh: apagando volumes; todos os eventos e reservas existentes serão perdidos."
        compose down -v -t 30 >/dev/null 2>&1 || true
        ok "Volumes removidos"
    fi

    # Banco e broker primeiro. O Kafka UI fica de fora deste comando de propósito: ele
    # declara `depends_on: kafka: service_healthy`, e incluí-lo aqui faria o compose
    # esperar pela saúde do Kafka por conta própria — espera que falha se o container
    # ainda estiver encerrando um ciclo anterior. Quem espera pela saúde aqui é este
    # script, logo abaixo, com mensagem própria.
    compose_up postgres kafka
    ok "PostgreSQL e Kafka solicitados"

    # O application.yml valida o schema na subida e o Flyway migra: subir a aplicação
    # antes do banco aceitar conexões só produz um stack trace confuso. Daí a espera
    # pelo healthcheck, que é o sinal real de "pronto para uso".
    wait_for_service_health postgres "PostgreSQL"
    wait_for_service_health kafka "Kafka"

    # Com o Kafka já saudável, a dependência do Kafka UI é satisfeita imediatamente.
    compose_up kafka-ui
    ok "Kafka UI solicitada"
}

wait_for_service_health() {
    local service="$1" label="$2" cid waited=0 status

    cid="$(service_container "$service")"
    if [ -z "$cid" ]; then
        err "Container do serviço '$service' não foi criado."
        return 1
    fi

    # Serviço sem healthcheck declarado: basta estar rodando.
    if [ "$(docker inspect -f '{{if .State.Health}}yes{{else}}no{{end}}' "$cid")" = "no" ]; then
        ok "$label em execução (sem healthcheck declarado)"
        return 0
    fi

    printf '  %saguardando %s ficar saudável' "$C_DIM" "$label"
    while [ "$waited" -lt "$INFRA_HEALTH_TIMEOUT" ]; do
        status="$(docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || echo unknown)"
        case "$status" in
            healthy)
                printf '%s\n' "$C_RESET"
                ok "$label saudável"
                return 0
                ;;
            unhealthy)
                printf '%s\n' "$C_RESET"
                err "$label subiu mas está unhealthy. Log: $COMPOSE logs $service"
                return 1
                ;;
        esac
        printf '.'
        sleep 2
        waited=$((waited + 2))
    done

    printf '%s\n' "$C_RESET"
    err "$label não ficou saudável em ${INFRA_HEALTH_TIMEOUT}s. Log: $COMPOSE logs $service"
    return 1
}

# --------------------------------------------------------------------------------------
# 3. Backend
# --------------------------------------------------------------------------------------

start_backend() {
    step "Subindo o backend (Spring Boot)"
    cd "$ROOT_DIR"
    mkdir -p "$RUN_DIR"

    if backend_up; then
        ok "Backend já estava no ar em $HEALTH_URL"
        return 0
    fi

    if [ "$FAST_EXPIRATION" = "yes" ]; then
        export RESERVATION_TTL=25s
        export RESERVATION_EXPIRATION_INTERVAL=PT5S
        warn "--fast-expiration: reservas expiram em 25s e a varredura roda a cada 5s."
        info "É a configuração para demonstrar o fluxo de expiração ao vivo, não um padrão de uso."
    fi

    : > "$BACKEND_LOG"
    SERVER_PORT="$BACKEND_PORT" nohup ./gradlew bootRun --console=plain >> "$BACKEND_LOG" 2>&1 &
    local pid=$!
    echo "$pid" > "$BACKEND_PID_FILE"

    info "PID $pid · log em $BACKEND_LOG"
    printf '  %saguardando o backend responder em %s' "$C_DIM" "$HEALTH_URL"

    local waited=0
    while [ "$waited" -lt "$BACKEND_BOOT_TIMEOUT" ]; do
        # Processo morto: esperar o resto do timeout não muda nada, e o log é o que importa.
        if ! kill -0 "$pid" 2>/dev/null; then
            printf '%s\n' "$C_RESET"
            err "O backend terminou antes de ficar pronto. Últimas linhas do log:"
            printf '%s\n' "$C_DIM"
            tail -n 30 "$BACKEND_LOG" >&2 || true
            printf '%s\n' "$C_RESET"
            rm -f "$BACKEND_PID_FILE"
            return 1
        fi
        if backend_up; then
            printf '%s\n' "$C_RESET"
            # Quem escuta na porta é a JVM da aplicação: é este o PID que `--stop` precisa.
            local app_pid
            app_pid="$(port_pid "$BACKEND_PORT" || true)"
            if [ -n "$app_pid" ]; then
                echo "$app_pid" > "$BACKEND_APP_PID_FILE"
            fi
            ok "Backend saudável em $HEALTH_URL (gradle $pid, app ${app_pid:-?})"
            return 0
        fi
        printf '.'
        sleep 3
        waited=$((waited + 3))
    done

    printf '%s\n' "$C_RESET"
    err "O backend não respondeu em ${BACKEND_BOOT_TIMEOUT}s. Log: $BACKEND_LOG"
    return 1
}

stop_backend() {
    local stopped=0 knew_pid=0 pid

    # 1. A JVM da aplicação, pelo PID exato registrado na subida.
    if [ -f "$BACKEND_APP_PID_FILE" ]; then
        knew_pid=1
        pid="$(cat "$BACKEND_APP_PID_FILE")"
        if kill -0 "$pid" 2>/dev/null; then
            kill_tree "$pid"
            stopped=1
        fi
        rm -f "$BACKEND_APP_PID_FILE"
    fi

    # 2. O cliente gradlew que lançou o bootRun.
    if [ -f "$BACKEND_PID_FILE" ]; then
        knew_pid=1
        pid="$(cat "$BACKEND_PID_FILE")"
        if kill -0 "$pid" 2>/dev/null; then
            kill_tree "$pid"
            stopped=1
        fi
        rm -f "$BACKEND_PID_FILE"
    fi

    # 3. Rede de segurança, SÓ quando não havia PID registrado — o caso de um backend
    #    iniciado à mão (`./gradlew bootRun`) fora deste script. Fazer isso sempre seria
    #    perigoso: o pkill por classe principal não distingue instâncias, e derrubaria
    #    também um backend que outro setup.sh acabou de subir.
    if [ "$knew_pid" -eq 0 ] && pgrep -f "$APP_MAIN_CLASS" >/dev/null 2>&1; then
        warn "Nenhum PID registrado por este script; encerrando o backend encontrado por classe principal."
        pkill -f "$APP_MAIN_CLASS" 2>/dev/null || true
        stopped=1
    fi

    if [ "$stopped" -eq 1 ]; then
        ok "Backend encerrado"
    else
        info "Backend não estava em execução"
    fi
}

# --------------------------------------------------------------------------------------
# 4. Frontend (delegado)
# --------------------------------------------------------------------------------------

start_frontend() {
    step "Subindo o frontend (delegado ao setup.sh do frontend)"
    FRONTEND_PORT="$FRONTEND_PORT" BACKEND_URL="http://localhost:$BACKEND_PORT" \
        "$FRONTEND_DIR/setup.sh" --background
}

stop_frontend() {
    if [ -f "$FRONTEND_DIR/setup.sh" ]; then
        FRONTEND_PORT="$FRONTEND_PORT" "$FRONTEND_DIR/setup.sh" --stop
    fi
}

# --------------------------------------------------------------------------------------
# 5. Encerramento e status
# --------------------------------------------------------------------------------------

stop_all() {
    step "Encerrando o Flash Booking"
    stop_frontend
    stop_backend

    cd "$ROOT_DIR"
    if [ -n "$COMPOSE" ] && docker info >/dev/null 2>&1; then
        # -t 30: o Kafka precisa de mais que os 10s padrão para encerrar sozinho. Sem
        # isso o compose o mata com SIGKILL, o container fica com exit 137, e a próxima
        # subida tropeça nesse estado.
        info "Parando containers (o Kafka pode levar alguns segundos)…"
        compose stop -t 30 >/dev/null 2>&1 || true
        ok "Containers parados (volumes preservados — use --fresh para apagar os dados)"
    fi
}

# Encerramento por Ctrl+C no modo interativo: derruba o que este script subiu.
on_interrupt() {
    printf '\n'
    warn "Interrupção recebida."
    stop_all
    printf '\n'
    exit 130
}

show_status() {
    step "Status"
    cd "$ROOT_DIR"
    detect_compose

    if [ -n "$COMPOSE" ] && docker info >/dev/null 2>&1; then
        for service in postgres kafka kafka-ui; do
            if service_running "$service"; then
                ok "container $service: em execução"
            else
                info "container $service: parado"
            fi
        done
    else
        warn "Docker indisponível — status dos containers desconhecido"
    fi

    if backend_up; then
        ok "backend: saudável em $HEALTH_URL"
    elif port_in_use "$BACKEND_PORT"; then
        warn "backend: porta $BACKEND_PORT ocupada, mas /actuator/health não respondeu UP"
    else
        info "backend: fora do ar"
    fi

    if http_ok "$FRONTEND_URL"; then
        ok "frontend: respondendo em $FRONTEND_URL"
    else
        info "frontend: fora do ar"
    fi
}

follow_logs() {
    local logs=""
    [ -f "$BACKEND_LOG" ] && logs="$logs $BACKEND_LOG"
    [ -f "$FRONTEND_DIR/.run/frontend.log" ] && logs="$logs $FRONTEND_DIR/.run/frontend.log"

    if [ -z "$logs" ]; then
        warn "Nenhum log encontrado — o setup.sh ainda não subiu nada nesta máquina."
        exit 0
    fi

    info "Ctrl+C interrompe o acompanhamento (não encerra os serviços)."
    printf '\n'
    # shellcheck disable=SC2086
    tail -n 40 -f $logs
}

print_summary() {
    local with_frontend="$1"

    printf '\n%s' "$C_GREEN$C_BOLD"
    printf '════════════════════════════════════════════════════════════════\n'
    printf '  Flash Booking está no ar\n'
    printf '════════════════════════════════════════════════════════════════%s\n\n' "$C_RESET"

    if [ "$with_frontend" = "yes" ]; then
        printf '  %sFrontend%s    %s\n' "$C_BOLD" "$C_RESET" "$FRONTEND_URL"
    fi
    printf '  %sAPI%s         http://localhost:%s\n' "$C_BOLD" "$C_RESET" "$BACKEND_PORT"
    printf '  %sSwagger%s     http://localhost:%s/swagger-ui.html\n' "$C_BOLD" "$C_RESET" "$BACKEND_PORT"
    printf '  %sHealth%s      %s\n' "$C_BOLD" "$C_RESET" "$HEALTH_URL"
    printf '  %sKafka UI%s    %s\n' "$C_BOLD" "$C_RESET" "$KAFKA_UI_URL"

    printf '\n  %sLogs%s\n' "$C_BOLD" "$C_RESET"
    printf '    backend     %s\n' "$BACKEND_LOG"
    if [ "$with_frontend" = "yes" ]; then
        printf '    frontend    %s\n' "$FRONTEND_DIR/.run/frontend.log"
    fi
    printf '    acompanhar  ./setup.sh --logs\n'

    printf '\n  %sComeçar a demonstração%s\n' "$C_BOLD" "$C_RESET"
    if [ "$with_frontend" = "yes" ]; then
        printf '    Abra %s e crie um evento na tela inicial.\n' "$FRONTEND_URL"
    else
        cat <<EOF
    curl -s -X POST localhost:$BACKEND_PORT/events \\
      -H 'Content-Type: application/json' \\
      -d '{"name":"Show de Abertura","capacity":500}'
EOF
    fi

    printf '\n  %sEncerrar%s      ./setup.sh --stop   (ou Ctrl+C aqui)\n\n' "$C_BOLD" "$C_RESET"
}

# --------------------------------------------------------------------------------------
# Entrada
# --------------------------------------------------------------------------------------

MODE="up"
WITH_FRONTEND="yes"
FRESH="no"
FAST_EXPIRATION="no"

while [ $# -gt 0 ]; do
    case "$1" in
        --check)           MODE="check" ;;
        --backend-only)    WITH_FRONTEND="no" ;;
        --stop)            MODE="stop" ;;
        --status)          MODE="status" ;;
        --logs)            MODE="logs" ;;
        --fresh)           FRESH="yes" ;;
        --fast-expiration) FAST_EXPIRATION="yes" ;;
        --help|-h)
            awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
            exit 0
            ;;
        *)
            err "Opção desconhecida: $1"
            info "Use --help para ver as opções."
            exit 2
            ;;
    esac
    shift
done

printf '%sFlash Booking — setup%s\n' "$C_BOLD" "$C_RESET"
printf '%sbackend:  %s%s\n' "$C_DIM" "$ROOT_DIR" "$C_RESET"
[ "$WITH_FRONTEND" = "yes" ] && printf '%sfrontend: %s%s\n' "$C_DIM" "$FRONTEND_DIR" "$C_RESET"

case "$MODE" in
    status)
        show_status
        exit 0
        ;;
    logs)
        follow_logs
        exit 0
        ;;
    stop)
        detect_compose
        stop_all
        printf '\n'
        exit 0
        ;;
    check)
        if ! check_dependencies "$WITH_FRONTEND"; then
            exit 1
        fi
        printf '\n%s✓ Tudo pronto. Rode ./setup.sh para subir.%s\n\n' "$C_GREEN$C_BOLD" "$C_RESET"
        exit 0
        ;;
esac

# --- subida completa ---
if ! check_dependencies "$WITH_FRONTEND"; then
    printf '\n%sNada foi iniciado. Ajuste os itens acima e rode de novo.%s\n\n' "$C_DIM" "$C_RESET"
    exit 1
fi

trap on_interrupt INT TERM

start_infrastructure
start_backend

if [ "$WITH_FRONTEND" = "yes" ]; then
    start_frontend
fi

print_summary "$WITH_FRONTEND"

# Os serviços rodam em segundo plano; este processo fica vivo só para que Ctrl+C
# encerre tudo de uma vez. Sair daqui com --stop depois é igualmente válido.
info "Serviços em segundo plano. Ctrl+C encerra tudo; fechar o terminal os mantém no ar."
while true; do
    sleep 60
    if ! backend_up; then
        printf '\n'
        warn "O backend parou de responder. Últimas linhas do log:"
        printf '%s\n' "$C_DIM"
        tail -n 20 "$BACKEND_LOG" || true
        printf '%s\n' "$C_RESET"
        exit 1
    fi
done
