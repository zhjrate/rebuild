/*
rebuild - Building your business-systems freely.
Copyright (C) 2019 devezhao <zhaofang123@gmail.com>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package com.rebuild.server.service.feeds;

import cn.devezhao.persist4j.PersistManagerFactory;
import cn.devezhao.persist4j.Record;
import com.rebuild.server.metadata.EntityHelper;
import com.rebuild.server.service.BaseService;

/**
 * TODO
 *
 * @author devezhao
 * @since 2019/11/4
 */
public class FeedsService extends BaseService {

    protected FeedsService(PersistManagerFactory aPMFactory) {
        super(aPMFactory);
    }

    @Override
    public int getEntityCode() {
        return EntityHelper.Feeds;
    }

    @Override
    public Record create(Record record) {
        return super.create(record);
    }

    @Override
    public Record update(Record record) {
        return super.update(record);
    }
}
