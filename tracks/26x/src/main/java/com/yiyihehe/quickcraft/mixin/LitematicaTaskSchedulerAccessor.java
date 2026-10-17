package com.yiyihehe.quickcraft.mixin;

import fi.dy.masa.litematica.scheduler.ITask;
import fi.dy.masa.litematica.scheduler.TaskScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(value = TaskScheduler.class, remap = false)
public interface LitematicaTaskSchedulerAccessor {
    @Accessor("tasksToAdd")
    List<ITask> quickcraft$getQueuedTasks();
}
