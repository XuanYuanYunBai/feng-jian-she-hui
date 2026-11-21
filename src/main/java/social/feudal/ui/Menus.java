package social.feudal.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

public final class Menus {
    private Menus() {}

    public static ActionButton command(String label, String command) {
        return ActionButton.create(Component.text(label), null, 128,
                DialogAction.staticAction(ClickEvent.runCommand("/" + command)));
    }

    public static DialogBody text(String value) {
        return DialogBody.plainMessage(Component.text(value), 320);
    }

    public static void show(Player player, String title, List<DialogBody> body,
                            List<DialogInput> inputs, List<ActionButton> buttons, int columns) {
        var base = DialogBase.builder(Component.text(title, NamedTextColor.GOLD))
                .body(body).inputs(inputs).canCloseWithEscape(true).pause(false).build();
        var close = ActionButton.create(Component.text("关闭"), null, 128, null);
        player.showDialog(Dialog.create(builder -> builder.empty().base(base)
                .type(buttons.isEmpty() ? DialogType.notice(close) : DialogType.multiAction(buttons, close, columns))));
    }
}
