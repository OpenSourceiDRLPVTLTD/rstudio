/*
 * CppCompletionManager.java
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.source.editors.text.cpp;


import org.rstudio.core.client.Invalidation;
import org.rstudio.core.client.Rectangle;
import org.rstudio.core.client.command.KeyboardShortcut;
import org.rstudio.core.client.events.SelectionCommitEvent;
import org.rstudio.core.client.events.SelectionCommitHandler;
import org.rstudio.core.client.js.JsUtil;
import org.rstudio.studio.client.common.filetypes.TextFileType;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.workbench.views.console.shell.assist.CompletionListPopupPanel;
import org.rstudio.studio.client.workbench.views.console.shell.assist.CompletionManager;
import org.rstudio.studio.client.workbench.views.console.shell.assist.CompletionUtils;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorDisplay;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorLineWithCursorPosition;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorSelection;
import org.rstudio.studio.client.workbench.views.console.shell.editor.InputEditorUtil;
import org.rstudio.studio.client.workbench.views.source.editors.text.NavigableSourceEditor;
import org.rstudio.studio.client.workbench.views.source.model.CppServerOperations;

import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.NativeEvent;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.logical.shared.CloseEvent;
import com.google.gwt.event.logical.shared.CloseHandler;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.PopupPanel.PositionCallback;

public class CppCompletionManager implements CompletionManager
{
   public CppCompletionManager(InputEditorDisplay input,
                               NavigableSourceEditor navigableSourceEditor,
                               InitCompletionFilter initFilter,
                               CppServerOperations server,
                               CompletionManager rCompletionManager)
   {
      input_ = input;
      navigableSourceEditor_ = navigableSourceEditor;
      initFilter_ = initFilter;
      server_ = server;
      rCompletionManager_ = rCompletionManager;
      requester_ = new CppCompletionRequester(server_);
      
      input_.addClickHandler(new ClickHandler()
      {
         public void onClick(ClickEvent event)
         {
            invalidatePendingRequests();
         }
      });
   }
 
   // close the completion popup (if any)
   @Override
   public void close()
   {
      // delegate to R mode if necessary
      if (isCursorInRMode())
      {
         rCompletionManager_.close();
      }
      else
      {
         closeCompletionPopup();
      }
   }
   
   
   // perform completion at the current cursor location
   @Override
   public void codeCompletion()
   {
      // delegate to R mode if necessary
      if (isCursorInRMode())
      {
         rCompletionManager_.codeCompletion();
      }
      // check whether it's okay to do a completion
      else if ((popup_ == null) && shouldComplete(null))
      {
         beginSuggest(true,    // flush cache
                      false);  // explicit (can show none found ui)
      }
   }

   // go to help at the current cursor location
   @Override
   public void goToHelp()
   {
      // delegate to R mode if necessary
      if (isCursorInRMode())
      {
         rCompletionManager_.goToHelp();
      }
      else
      {
         // TODO: go to help
         
         // determine current line and cursor position
         @SuppressWarnings("unused")
         InputEditorLineWithCursorPosition lineWithPos = 
                         InputEditorUtil.getLineWithCursorPosition(input_);
      }
   }

   // find the definition of the function at the current cursor location
   @Override
   public void goToFunctionDefinition()
   {  
      // delegate to R mode if necessary
      if (isCursorInRMode())
      {
         rCompletionManager_.goToFunctionDefinition();
      }
      else
      {
         // TODO: go to function definition
         
         // determine current line and cursor position
         @SuppressWarnings("unused")
         InputEditorLineWithCursorPosition lineWithPos = 
                         InputEditorUtil.getLineWithCursorPosition(input_);
      }
   }
   
   // return false to indicate key not handled
   @Override
   public boolean previewKeyDown(NativeEvent event)
   {
      // delegate to R mode if appropriate
      if (isCursorInRMode())
         return rCompletionManager_.previewKeyDown(event);
      
      // if there is no completion popup visible then check
      // for a completion request or help/goto key-combo
      int modifier = KeyboardShortcut.getModifierValue(event);
      if (popup_ == null)
      { 
         // check for user completion key combo 
         if (CompletionUtils.isCompletionRequest(event, modifier) &&
             shouldComplete(event)) 
         {
            beginSuggest(true,    // flush cache
                         false);  // explicit (can show none found ui)
            
            return true;  
         }
         else if (event.getKeyCode() == 112 // F1
                  && modifier == KeyboardShortcut.NONE)
         {
            goToHelp();
            return true;
         }
         else if (event.getKeyCode() == 113 // F2
                  && modifier == KeyboardShortcut.NONE)
         {
            goToFunctionDefinition();
            return true;
         }
         else
         {
            return false;
         }
      }
      // otherwise handle keys within the completion popup
      else
      { 
         // bare modifiers do nothing
         int keyCode = event.getKeyCode();
         if (keyCode == KeyCodes.KEY_SHIFT ||
             keyCode == KeyCodes.KEY_CTRL ||
             keyCode == KeyCodes.KEY_ALT)
         {          
            return false ; 
         }
         
         // escape and left keys close the popup
         if (event.getKeyCode() == KeyCodes.KEY_ESCAPE ||
             event.getKeyCode() == KeyCodes.KEY_LEFT)
         {
            invalidatePendingRequests();
            return true;
         }
          
         // enter/tab/right accept the current selection
         else if (event.getKeyCode() == KeyCodes.KEY_ENTER ||
                  event.getKeyCode() == KeyCodes.KEY_TAB ||
                  event.getKeyCode() == KeyCodes.KEY_RIGHT)
         {
            context_.onSelected(popup_.getSelectedValue());
            return true;
         }
         
         // basic navigation keys
         else if (event.getKeyCode() == KeyCodes.KEY_UP)
            return popup_.selectPrev();
         else if (event.getKeyCode() == KeyCodes.KEY_DOWN)
            return popup_.selectNext();
         else if (event.getKeyCode() == KeyCodes.KEY_PAGEUP)
            return popup_.selectPrevPage() ;
         else if (event.getKeyCode() == KeyCodes.KEY_PAGEDOWN)
            return popup_.selectNextPage() ;
         else if (event.getKeyCode() == KeyCodes.KEY_HOME)
            return popup_.selectFirst() ;
         else if (event.getKeyCode() == KeyCodes.KEY_END)
            return popup_.selectLast() ;
         
         // non c++ identifier keys (that aren't navigational) close the popup
         else if (!isCppIdentifierKey(event))
         {
            invalidatePendingRequests();
            return false;
         }
         
         // otherwise leave it alone
         else
         {   
            return false;
         }
      }
   }

   // return false to indicate key not handled
   @Override
   public boolean previewKeyPress(char c)
   {
      // delegate to R mode if necessary
      if (isCursorInRMode())
         return rCompletionManager_.previewKeyPress(c);
      
      // if the popup is showing and this is a valid C++ identifier key
      // then re-execute the completion request
      if ((popup_ != null) && isCppIdentifierChar(c))
      {
         Scheduler.get().scheduleDeferred(new ScheduledCommand()
         {
            @Override
            public void execute()
            {
               beginSuggest(false,   // don't flush cache
                            false);  // explicit (can show none found ui)
            }
         });
         
         return false;
      }
      
      // if there is no popup and this key should begin a completion
      // then do that
      else if ((popup_ == null) && triggerCompletion(c))
      {
         Scheduler.get().scheduleDeferred(new ScheduledCommand()
         {
            @Override
            public void execute()
            {
               beginSuggest(true,    // flush cache 
                            true);   // implicit (don't show none found ui)
            }
         });
         
         return false;
      }
      
      else if (CompletionUtils.handleEncloseSelection(input_, c))
      {
         return true;
      }
      else
      {
         return false;
      }
   }
   
   private boolean triggerCompletion(char c)
   {
      return false;
   }
  
  
   private void invalidatePendingRequests()
   {
      invalidatePendingRequests(true) ;
   }

   private void invalidatePendingRequests(boolean flushCache)
   {
      completionRequestInvalidation_.invalidate();
      closeCompletionPopup();
      if (flushCache)
         requester_.flushCache() ;
   }
   
   private boolean beginSuggest(boolean flushCache, boolean implicit)
   {
      if (!input_.isSelectionCollapsed())
         return false ;
      
      invalidatePendingRequests(flushCache) ;
      
      InputEditorSelection selection = input_.getSelection() ;
      if (selection == null)
         return false;
      
      String line = input_.getText() ;
      
      boolean canAutoAccept = flushCache;
      context_ = new CompletionRequestContext(
                        completionRequestInvalidation_.getInvalidationToken(),
                        selection,
                        canAutoAccept);
      
      requester_.getCompletions(line,
                                selection.getStart().getPosition(),
                                implicit,
                                context_);
      
      return true ;   
   }
  
   
   private final class CompletionRequestContext extends
                                 ServerRequestCallback<CppCompletionResult>
   {  
      public CompletionRequestContext(Invalidation.Token token,
                                      InputEditorSelection selection,
                                      boolean canAutoAccept)
      {
         invalidationToken_ = token;
         selection_ = selection;
         canAutoAccept_ = canAutoAccept;
      }
      
      @Override 
      public void onResponseReceived(CppCompletionResult result)
      {
         if (invalidationToken_.isInvalid())
            return ;
         
         if (result.getCompletions().length() == 0)
         {
            popup_ = new CompletionListPopupPanel(new String[0]);
            popup_.setText("(No matching commands)");
         }
         else
         {
            String[] entries = JsUtil.toStringArray(result.getCompletions());
            popup_ = new CompletionListPopupPanel(entries);
         }
         
         popup_.setMaxWidth(input_.getBounds().getWidth());
         popup_.setPopupPositionAndShow(new PositionCallback()
         {
            public void setPosition(int offsetWidth, int offsetHeight)
            {
               Rectangle bounds = input_.getCursorBounds();

               int top = bounds.getTop() + bounds.getHeight();
              
               popup_.selectFirst();
               popup_.setPopupPosition(bounds.getLeft(), top);
            }
         });

         popup_.addSelectionCommitHandler(new SelectionCommitHandler<String>()
         {
            public void onSelectionCommit(SelectionCommitEvent<String> e)
            {
               onSelected(e.getSelectedItem());
            }
         });
         
         popup_.addCloseHandler(new CloseHandler<PopupPanel>() {

            @Override
            public void onClose(CloseEvent<PopupPanel> event)
            {
               popup_ = null;          
            }
            
         });
      }
      
      @Override
      public void onError(ServerError error)
      {
         if (invalidationToken_.isInvalid())
            return ;
         
         popup_ = new CompletionListPopupPanel(new String[0]);
         popup_.setText(error.getUserMessage());
      }
      
      public void onSelected(String completion)
      {
         if (invalidationToken_.isInvalid())
            return;
         
         closeCompletionPopup();
         
         requester_.flushCache();
         
         input_.insertCode(completion);
      }

      private final Invalidation.Token invalidationToken_;
      @SuppressWarnings("unused")
      private final InputEditorSelection selection_;
      @SuppressWarnings("unused")
      private final boolean canAutoAccept_;
   }

   
   private boolean shouldComplete(NativeEvent event)
   {
      return initFilter_ == null || initFilter_.shouldComplete(event);
   }

   private boolean isCursorInRMode()
   {
      String mode = input_.getLanguageMode(input_.getCursorPosition());
      if (mode == null)
         return false;
      if (mode.equals(TextFileType.R_LANG_MODE))
         return true;
      return false;
   }
   
   private static boolean isCppIdentifierKey(NativeEvent event)
   {
      if (event.getAltKey() || event.getCtrlKey() || event.getMetaKey())
         return false ;
      
      int keyCode = event.getKeyCode() ;
      if (keyCode >= 'a' && keyCode <= 'z')
         return true ;
      if (keyCode >= 'A' && keyCode <= 'Z')
         return true ;
      if (keyCode == 189 && event.getShiftKey()) // underscore
         return true ;
     
      if (event.getShiftKey())
         return false ;
      
      if (keyCode >= '0' && keyCode <= '9')
         return true ;
      
      return false ;
   }
   
   public static boolean isCppIdentifierChar(char c)
   {
      return ((c >= 'a' && c <= 'z') || 
              (c >= 'A' && c <= 'Z') || 
              (c >= '0' && c <= '9') ||
               c == '_');
   }
  
   private void closeCompletionPopup()
   {
      if (popup_ != null)
      {
         popup_.hide();
         popup_ = null;
      }
   }
  
   private final InputEditorDisplay input_ ;
   @SuppressWarnings("unused")
   private final NavigableSourceEditor navigableSourceEditor_;
   private CompletionListPopupPanel popup_;
   private final CppServerOperations server_;
   private final CppCompletionRequester requester_ ;
   private CompletionRequestContext context_;
   private final InitCompletionFilter initFilter_ ;
   private final CompletionManager rCompletionManager_;
   private final Invalidation completionRequestInvalidation_ = new Invalidation();
   
  

}
