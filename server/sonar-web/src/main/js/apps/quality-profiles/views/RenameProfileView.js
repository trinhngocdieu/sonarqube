/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import ModalFormView from '../../../components/common/modal-form';
import Template from '../templates/quality-profiles-rename-profile.hbs';
import { renameProfile } from '../../../api/quality-profiles';

export default ModalFormView.extend({
  template: Template,

  onFormSubmit () {
    ModalFormView.prototype.onFormSubmit.apply(this, arguments);
    this.sendRequest();
  },

  sendRequest () {
    const name = this.$('#rename-profile-name').val();
    renameProfile(this.options.profile.key, name)
        .then(profile => {
          this.destroy();
          this.trigger('done', profile);
        })
        .catch(e => {
          if (e.response.status === 400) {
            this.enableForm();
            e.response.json().then(r => this.showErrors(r.errors, r.warnings));
          }
        });
  },

  serializeData () {
    return this.options.profile;
  }
});

