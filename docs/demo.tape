Output docs/demo.gif

Set Theme "Dracula"
Set FontSize 20
Set Width 1280
Set Height 720
Set Padding 24
Set TypingSpeed 70ms
Set Shell "bash"

Type "cd /workspace"
Enter
Sleep 300ms

Type "export RPGM_API_HOST=host.docker.internal RPGM_HOST_WORKSPACE=C:/Users/samde/dev/rpg-master"
Enter
Sleep 300ms

Type "./rpgm status"
Enter
Sleep 3000ms

Type "./rpgm ingest pdfs/phb.pdf dnd-5e-phb"
Enter
Sleep 35000ms

Type "./rpgm ask What is the Fireball spell?"
Enter
Sleep 30000ms
