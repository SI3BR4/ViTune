# Orientações para alterações no ViTune

- Antes de alterar o app, leia o código relacionado e preserve as personalizações da fork e os dados existentes.
- Revise as mudanças em busca de regressões, inclusive nos fluxos que compartilham rotas, estados, reprodução e persistência com o código alterado.
- Para mudanças de navegação ou interface, teste os controles afetados abrindo seus destinos, voltando e alternando entre telas; confira também a apresentação em português e com fonte ampliada quando pertinente.
- Para mudanças de reprodução, downloads, biblioteca ou importação, verifique os comportamentos relevantes e a preservação dos dados; não suponha que compilar, por si só, comprova esses fluxos.
- Execute os testes e a compilação adequados. A compilação existente no GitHub executa testes unitários e testes de navegação em emulador; amplie a cobertura quando necessário para o defeito corrigido.
- Só informe que uma verificação passou quando houver resultado comprovado. Relate ao usuário limitações, testes não executados e falhas pendentes.
- Nunca publique IPs, credenciais, arquivos CSV pessoais ou dados da biblioteca do usuário.
