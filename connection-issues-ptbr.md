## O que fazer quando o Warpinator não consegue encontrar ou conectar-se ao outro dispositivo

Este documento será atualizado quando for descoberto algum problema comum.

- Certifique-se de que você tem a [versão mais recente do App](https://github.com/slowscript/warpinator-android/releases). A mínima versão requerida para a variante original do Linux é 1.0.9

- Certifique-se de que ambos os dispositivos estejam na mesma rede (mesmo ponto de acesso WiFi e roteador)

- Se você mudar qualquer coisa na sua rede (ou alterar qualquer coisa com a intenção de resolver algum problema), reinicie o Software em ambos dispositivos para garantir que as modificações entrem em vigor.

- Tente usar o Hotspot no seu celular para descobrir se o problema é causado pelas configurações no seu roteador. (Não se esqueça de reiniciar o App e conectar outros dispositivos ao Hotspot)

- Não use uma VPN (a menos que você saiba o que está fazendo).

- Certifique-se de que o Firewall no seu computador/celular e roteador permitam o tráfego tanto para o protocolo de descoberta (mDNS, a Porta 5353 de UDP) quanto para o protocolo de transferência de arquivos (UDP e TCP, a Porta que está nas configurações - o padrão é 42000, você não deveria alterá-la a menos que causa problemas).
Desabilite temporariamente o Firewall, se for necessário.

- Seu roteador pode separar a rede entre WiFi e ethernet LAN ou mesmo entre todos os clientes.
Certifique-se de que isto esteja desativado.

- Você deveria ser capaz de executar um Ping entre os dois dispositivos.
Caso contrário, é um problema com a sua rede.

- Verifique o endereço de IP na versão Desktop do Warpinator (canto inferior direito da janela).
Às vezes ele obtém o IP de uma interface de rede errada.

- Executando `nc -zvu 192.168.xxx.xxx 5353` com o IP do seu celular enquanto o Warpinator estiver funcionando deveria resultar em `192.168.xxx.xxx 5353 port [udp/mdns] succeeded!`.
Caso contrário, verifique no registro de depuração (instruções abaixo) se ele contém algo tipo `Failed to init JmDNS`.
Se for assim, então por favor abra um [Issue](https://github.com/slowscript/warpinator-android/issues/new) e anexe o registro de depuração.

- Se os dispositivos se descobrem mas não conseguem conectar, tente reconectar algumas vezes (usando o botão ao lado do ícone de status).
A versão do Linux tenta reconectar-se automaticamente a cada 30 segundos.
Se isto também falhar, abra um 'Issue' com o registro e a descrição sobre o que você tentou até agora.

**Como obter o registro de depuração:**

Vá às configurações, ative o "Exportar o registro de depuração" e reinicie o App.
Em seguida você o encontrará em `Android/data/slowscript.warpinator/files`
