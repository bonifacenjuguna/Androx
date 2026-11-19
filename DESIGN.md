# Androx UI design notes

Goal: look and behave like a first-party Android app (Material 3), in Androx's black / white / grey identity.

## Rules the code follows
- **Structure**: one root `Scaffold` owns bottom navigation, FAB and snackbar. Screens only add a small top app bar.
- **Top bars**: standard small `TopAppBar`, pinned, tints on scroll. No large collapsing headers or hero blocks.
- **Lists, not cards**: Automations, History, Settings and Home use plain `ListItem` rows. Cards are used only for Builder steps.
- **Controls**: stock M3 Switch, FilterChip, radio dialogs, DropdownMenu with leading icons, AlertDialog, ModalBottomSheet, FAB.
- **Editor**: M3 full-screen-dialog pattern (X, title, Save); text tabs; code editor with line numbers; workflow canvas fills
  the tab with a FAB (add node), a Tidy button and a dismissible tip banner.
- **Motion**: fade-through between top-level tabs, horizontal shared-axis slide for the editor.
- **Colour**: monochrome only. Red appears only for errors and destructive confirmations.
- **Type**: stock Material 3 scale, no overrides.

## Logo
`brand/` and the vector drawables (`ic_launcher_foreground`, `ic_splash_logo`, `ic_androx_mark`, `ic_stat_androx`)
were regenerated from the supplied logo PNG.
